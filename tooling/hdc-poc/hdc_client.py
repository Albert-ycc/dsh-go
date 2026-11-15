#!/usr/bin/env python3
"""最小 hdc TCP 客户端（直连 hdcd，不经 hdc server，不用官方二进制）。

协议依据 OpenHarmony developtools_hdc：
  封包      src/common/session.cpp  HdcSessionBase::Send / OnRead / DecryptPayload
  握手结构  src/common/serial_struct.h  Descriptor<SessionHandShake> / <PayloadProtect>（protobuf 线格式）
  认证      src/common/auth.cpp  RsaSignAndBase64 / RsaSign；src/host/server.cpp  HandServerAuth
            src/daemon/daemon.cpp  HandDaemonAuth* 系列
  命令号    src/common/define_enum.h  HdcCommand

只依赖标准库 + cryptography。私钥只在内存里用于签名，不打印。
"""
import argparse
import base64
import os
import random
import socket
import struct
import sys
import time

from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding

BANNER = b"OHOS HDC"
PACKET_FLAG = b"HW"
VER_PROTOCOL = 1
VCODE = 0x09
HEAD_FMT = ">2s2sBHI"  # flag[2] reserve[2] protocolVer headSize(BE16) dataSize(BE32)，共 11 字节
HEAD_LEN = struct.calcsize(HEAD_FMT)
CLIENT_VERSION = b"Ver: 3.1.0e"

# AuthType（session.h）
AUTH_NONE, AUTH_TOKEN, AUTH_SIGNATURE, AUTH_PUBLICKEY, AUTH_OK, AUTH_FAIL, AUTH_SSL_TLS_PSK = range(7)

# HdcCommand（define_enum.h）
CMD_KERNEL_HANDSHAKE = 1
CMD_KERNEL_CHANNEL_CLOSE = 2
CMD_KERNEL_ECHO = 9
CMD_KERNEL_ECHO_RAW = 10
CMD_UNITY_EXECUTE = 1001
CMD_FILE_INIT = 3000

TLV_TAG_LEN = 16
TLV_VAL_LEN = 16


# ---------- protobuf 线格式（只实现 varint 与 length-delimited）----------
def _varint(n):
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            return bytes(out)


def _read_varint(buf, pos):
    shift = val = 0
    while True:
        b = buf[pos]
        pos += 1
        val |= (b & 0x7F) << shift
        if not b & 0x80:
            return val, pos
        shift += 7


def pb_encode(fields):
    """fields: [(tag, int | bytes)]；整数走 varint，bytes 走 length-delimited。"""
    out = bytearray()
    for tag, v in fields:
        if isinstance(v, int):
            out += _varint(tag << 3 | 0) + _varint(v)
        else:
            out += _varint(tag << 3 | 2) + _varint(len(v)) + v
    return bytes(out)


def pb_decode(buf):
    res, pos = {}, 0
    while pos < len(buf):
        key, pos = _read_varint(buf, pos)
        tag, wt = key >> 3, key & 7
        if wt == 0:
            v, pos = _read_varint(buf, pos)
        elif wt == 2:
            n, pos = _read_varint(buf, pos)
            v, pos = buf[pos:pos + n], pos + n
        else:
            raise ValueError("unsupported wire type %d" % wt)
        res[tag] = v
    return res


# ---------- TLV（base.cpp TlvAppend / TlvToStringMap）----------
def tlv_append(tag, val):
    return tag.encode().ljust(TLV_TAG_LEN) + str(len(val)).encode().ljust(TLV_VAL_LEN) + val


def tlv_parse(buf):
    res = {}
    while len(buf) >= TLV_TAG_LEN + TLV_VAL_LEN:
        tag = buf[:TLV_TAG_LEN].strip().decode(errors="replace")
        n = int(buf[TLV_TAG_LEN:TLV_TAG_LEN + TLV_VAL_LEN].strip() or b"0")
        body = buf[TLV_TAG_LEN + TLV_VAL_LEN:]
        if len(body) < n:
            break
        res[tag] = body[:n]
        buf = body[n:]
    return res


class HdcError(Exception):
    pass


class HdcClient:
    def __init__(self, host, port, key_path=None, hostname=None, debug=False):
        self.host, self.port = host, port
        self.key_path = key_path or os.path.expanduser("~/.harmony/hdckey")
        self.hostname = hostname or socket.gethostname()
        self.debug = debug
        self.sock = None
        self.rbuf = b""
        self.channel_id = random.randint(1, 0x7FFFFFFF)
        self.devname = None

    def log(self, *a):
        if self.debug:
            print("[hdc]", *a, file=sys.stderr)

    # ----- 封包层 -----
    def send_frame(self, channel, cmd, data=b""):
        prot = pb_encode([(1, channel), (2, cmd), (3, 0), (4, VCODE)])
        head = struct.pack(HEAD_FMT, PACKET_FLAG, b"\0\0", VER_PROTOCOL, len(prot), len(data))
        self.sock.sendall(head + prot + data)

    def recv_frame(self, timeout=15):
        self.sock.settimeout(timeout)
        while True:
            if len(self.rbuf) >= HEAD_LEN:
                flag, _, _, hs, ds = struct.unpack(HEAD_FMT, self.rbuf[:HEAD_LEN])
                if flag != PACKET_FLAG:
                    raise HdcError("bad packet flag %r" % flag)
                total = HEAD_LEN + hs + ds
                if len(self.rbuf) >= total:
                    prot = pb_decode(self.rbuf[HEAD_LEN:HEAD_LEN + hs])
                    data = self.rbuf[HEAD_LEN + hs:total]
                    self.rbuf = self.rbuf[total:]
                    if prot.get(4) != VCODE:
                        raise HdcError("bad vcode")
                    return prot.get(1, 0), prot.get(2, 0), data
            chunk = self.sock.recv(65536)
            if not chunk:
                raise HdcError("connection closed by hdcd")
            self.rbuf += chunk

    # ----- 握手 + RSA 认证 -----
    @staticmethod
    def _hs(auth_type, buf=b"", session_id=0, connect_key=b""):
        f = [(1, BANNER), (2, auth_type)]
        if session_id:
            f.append((3, session_id))
        if connect_key:
            f.append((4, connect_key))
        if buf:
            f.append((5, buf))
        f.append((6, CLIENT_VERSION))
        return pb_encode(f)

    def _sign(self, token):
        with open(self.key_path, "rb") as f:
            key = serialization.load_pem_private_key(f.read(), password=None)
        # auth.cpp RsaSign：SHA512 摘要 + RSA-PSS(MGF1-SHA512)，盐长 = 摘要长度(64)，结果标准 base64
        sig = key.sign(token, padding.PSS(mgf=padding.MGF1(hashes.SHA512()), salt_length=64), hashes.SHA512())
        return base64.b64encode(sig)

    def _pubkey_info(self):
        with open(self.key_path + ".pub", "rb") as f:
            pem = f.read()
        # auth.cpp GetPublicKeyinfo：hostname + '\f' + PEM 公钥（daemon 要求公钥恰好 625 字节才认已登记）
        return self.hostname.encode() + b"\x0c" + pem

    def connect(self, auth_timeout=60):
        self.sock = socket.create_connection((self.host, self.port), timeout=10)
        self.sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        session_id = random.randint(1, 0x7FFFFFFF)
        ck = ("%s:%d" % (self.host, self.port)).encode()
        # step1：host 先发 AUTH_NONE，TLV 声明支持 RSA_3072_SHA512（authtype=1）；不声明 encrypt_tcp 特性
        self.send_frame(0, CMD_KERNEL_HANDSHAKE,
                        self._hs(AUTH_NONE, tlv_append("authtype", b"1"), session_id, ck))
        deadline = time.time() + auth_timeout
        while time.time() < deadline:
            ch, cmd, data = self.recv_frame(timeout=max(1, deadline - time.time()))
            if cmd != CMD_KERNEL_HANDSHAKE:
                self.log("ignore cmd", cmd, len(data))
                continue
            hs = pb_decode(data)
            at = hs.get(2, 0)
            buf = hs.get(5, b"")
            self.log("daemon handshake authType=%d buf=%dB" % (at, len(buf)))
            if at == AUTH_PUBLICKEY:
                # 守护进程要公钥：未登记时手机此刻弹授权框，下一包要等用户点完
                self.send_frame(0, CMD_KERNEL_HANDSHAKE, self._hs(AUTH_PUBLICKEY, self._pubkey_info()))
            elif at == AUTH_SIGNATURE:
                self.send_frame(0, CMD_KERNEL_HANDSHAKE, self._hs(AUTH_SIGNATURE, self._sign(buf)))
            elif at == AUTH_SSL_TLS_PSK:
                raise HdcError("hdcd 要求 TLS-PSK 加密通道（AUTH_SSL_TLS_PSK），PoC 未实现")
            elif at == AUTH_OK:
                info = tlv_parse(buf)
                self.devname = info.get("devname", b"").decode(errors="replace")
                status = info.get("daemonauthstatus", b"").decode(errors="replace")
                emg = info.get("emgmsg", b"").decode(errors="replace")
                self.log("AUTH_OK devname=%r status=%r emg=%r" % (self.devname, status, emg))
                if status == "unauthorized" or "unauthorized" in emg.lower():
                    # 未授权提示，不是终态：继续等用户在手机上点允许
                    print("[hdc] " + emg.replace("\r\n", " "), file=sys.stderr)
                    continue
                return
            else:
                raise HdcError("unexpected authType %d" % at)
        raise HdcError("auth timeout")

    # ----- 命令 -----
    def exec_shell(self, command, timeout=60):
        """对应 `hdc shell <cmd>`：CMD_UNITY_EXECUTE，输出以 ECHO_RAW 帧返回，CHANNEL_CLOSE 结束。"""
        self.send_frame(self.channel_id, CMD_UNITY_EXECUTE, command.encode())
        out = bytearray()
        end = time.time() + timeout
        while time.time() < end:
            ch, cmd, data = self.recv_frame(timeout=max(1, end - time.time()))
            if ch != self.channel_id:
                continue
            if cmd == CMD_KERNEL_CHANNEL_CLOSE:
                return bytes(out)
            self.log("frame cmd=%d len=%d" % (cmd, len(data)))
            out += data
        raise HdcError("shell timeout")

    def close(self):
        if self.sock:
            self.sock.close()


def main():
    ap = argparse.ArgumentParser(description="最小 hdc TCP 客户端 PoC")
    ap.add_argument("--host", required=True)
    ap.add_argument("--port", type=int, required=True)
    ap.add_argument("--key", help="私钥路径，默认 ~/.harmony/hdckey（公钥为同名 .pub）")
    ap.add_argument("--debug", action="store_true")
    ap.add_argument("--auth-timeout", type=int, default=60, help="等待手机授权弹窗的秒数")
    sub = ap.add_subparsers(dest="op", required=True)
    s = sub.add_parser("shell")
    s.add_argument("command")
    r = sub.add_parser("recv", help="拉回设备文件：shell cat 管道方案")
    r.add_argument("remote")
    r.add_argument("local")
    a = ap.parse_args()

    c = HdcClient(a.host, a.port, a.key, debug=a.debug)
    try:
        c.connect(a.auth_timeout)
        if a.op == "shell":
            sys.stdout.buffer.write(c.exec_shell(a.command))
            sys.stdout.buffer.flush()
        else:
            recv_file(c, a.remote, a.local)
    except (HdcError, OSError) as e:
        print("hdc_client error: %s" % e, file=sys.stderr)
        sys.exit(1)
    finally:
        c.close()


def recv_file(c, remote, local):
    """兜底方案：shell cat + base64。官方 file recv（CMD_FILE_INIT 任务协议）未在 PoC 范围内实现。"""
    raw = c.exec_shell("base64 < %s" % remote, timeout=120)
    data = base64.b64decode(b"".join(raw.split()))
    with open(local, "wb") as f:
        f.write(data)
    print("recv %s -> %s (%d bytes)" % (remote, local, len(data)), file=sys.stderr)


if __name__ == "__main__":
    main()
