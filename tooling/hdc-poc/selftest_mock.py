#!/usr/bin/env python3
"""自测：本地假 hdcd 按 daemon.cpp 状态机应答，验证封包/protobuf/签名流程自洽。
这不是真机证据：假 daemon 是按同一份读码写的，只能抓实现笔误。"""
import base64, os, socket, struct, threading, sys
from cryptography.hazmat.primitives import hashes, serialization
from cryptography.hazmat.primitives.asymmetric import padding
import hdc_client as H

pub = serialization.load_pem_public_key(open(os.path.expanduser("~/.harmony/hdckey.pub"), "rb").read())
srv = socket.socket(); srv.bind(("127.0.0.1", 0)); srv.listen(1)
result = {}

def daemon():
    c, _ = srv.accept()
    cl = H.HdcClient("x", 0); cl.sock = c
    def hs(at, buf=b""):
        cl.send_frame(0, H.CMD_KERNEL_HANDSHAKE, H.pb_encode([(1, H.BANNER), (2, at), (5, buf)] if buf else [(1, H.BANNER), (2, at)]))
    ch, cmd, d = cl.recv_frame(); m = H.pb_decode(d)
    assert m[1] == H.BANNER and m.get(2, 0) == 0 and H.tlv_parse(m[5])["authtype"] == b"1"
    hs(H.AUTH_PUBLICKEY, H.tlv_append("authtype", b"1"))
    ch, cmd, d = cl.recv_frame(); m = H.pb_decode(d)
    assert m[2] == H.AUTH_PUBLICKEY and len(m[5].split(b"\x0c", 1)[1]) == 625
    token = os.urandom(20); hs(H.AUTH_SIGNATURE, token)
    ch, cmd, d = cl.recv_frame(); m = H.pb_decode(d)
    pub.verify(base64.b64decode(m[5]), token, padding.PSS(mgf=padding.MGF1(hashes.SHA512()), salt_length=padding.PSS.AUTO), hashes.SHA512())
    result["sig"] = "verified"
    hs(H.AUTH_OK, H.tlv_append("devname", b"mock"))
    ch, cmd, d = cl.recv_frame()
    assert cmd == H.CMD_UNITY_EXECUTE
    cl.send_frame(ch, H.CMD_KERNEL_ECHO_RAW, b"out:" + d); cl.send_frame(ch, H.CMD_KERNEL_CHANNEL_CLOSE, b"\x01")

t = threading.Thread(target=daemon); t.start()
c = H.HdcClient("127.0.0.1", srv.getsockname()[1]); c.connect(5)
out = c.exec_shell("echo hi"); t.join()
assert out == b"out:echo hi", out
print("mock selftest OK:", result, out)
