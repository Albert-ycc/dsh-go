import { spawn } from 'node:child_process';
import { RelayError } from './errors.mjs';

/** 单条 hdc 命令回显（stdout+stderr）累计上限：超过就杀掉，防止异常设备输出撑爆内存 */
export const MAX_OUTPUT_BYTES = 8 * 1024 * 1024;

/**
 * 全局串行的作业队列。作业是一次完整的对外请求（读屏含取回和清理，replaceText 含四步），
 * 同一时刻只有一个作业在跑，避免读屏读到动作中途的树（方案 4.7）。
 * 取消只杀 hdc 客户端子进程；设备端已经收到的 uitest 命令无法撤回。
 */
export class Hdc {
  constructor({ bin, maxDepth = 4, now = () => Date.now() }) {
    this.bin = bin;
    this.maxDepth = maxDepth;
    this.now = now;
    this.waiting = [];
    this.running = null;
  }

  /** 排队深度；soft 作业（后台探活）不计入，不能因为它让真实请求吃 429 */
  depth() {
    return this.waiting.filter((j) => !j.meta.soft).length + (this.running && !this.running.meta.soft ? 1 : 0);
  }

  /**
   * @param {{task?: string, kind: string, timeoutMs: number}} meta
   * @param {(ctx: JobContext) => Promise<any>} fn
   */
  submit(meta, fn) {
    if (!meta.soft && this.depth() >= this.maxDepth) {
      return Promise.reject(new RelayError('busy', 'queue full', { retryable: true }));
    }
    return new Promise((resolve, reject) => {
      const job = { meta, fn, resolve, reject, cancelled: false, child: null, executed: false };
      this.waiting.push(job);
      this.#pump();
    });
  }

  #pump() {
    if (this.running || this.waiting.length === 0) return;
    const job = this.waiting.shift();
    this.running = job;
    const ctx = new JobContext(this, job);
    Promise.resolve()
      .then(() => job.fn(ctx))
      .then(
        (v) => job.resolve(v),
        (e) => {
          if (e instanceof RelayError && job.executed) e.executed = true;
          job.reject(e);
        },
      )
      .finally(() => {
        ctx.dispose();
        this.running = null;
        this.#pump();
      });
  }

  #cancel(match) {
    let n = 0;
    this.waiting = this.waiting.filter((j) => {
      if (!match(j)) return true;
      n += 1;
      j.reject(new RelayError('cancelled', 'cancelled before start'));
      return false;
    });
    const r = this.running;
    if (r && match(r) && !r.cancelled) {
      r.cancelled = true;
      n += 1;
      if (r.child) r.child.kill('SIGKILL');
    }
    return n;
  }

  cancelTask(task) {
    return this.#cancel((j) => j.meta.task === task);
  }
  cancelAll() {
    return this.#cancel(() => true);
  }
}

class JobContext {
  constructor(hdc, job) {
    this.hdc = hdc;
    this.job = job;
    this.deadline = hdc.now() + job.meta.timeoutMs;
    this.timer = null;
  }

  /** observe 重连重试时重新给一份预算 */
  extendDeadline(ms) {
    this.deadline = this.hdc.now() + ms;
  }

  remaining() {
    return this.deadline - this.hdc.now();
  }

  get cancelled() {
    return this.job.cancelled;
  }

  assertLive() {
    if (this.job.cancelled) throw new RelayError('cancelled', 'cancelled');
  }

  /** 单个 hdc 子进程：永远 execFile 语义，参数数组，不经本机 shell。 */
  run(args, { timeoutMs = 5000, allowCancelled = false } = {}) {
    if (!allowCancelled) this.assertLive();
    const budget = allowCancelled ? timeoutMs : Math.min(timeoutMs, this.remaining());
    if (budget <= 0) {
      return Promise.reject(new RelayError('device_timeout', 'job budget exhausted', { retryable: true, executed: this.job.executed }));
    }
    this.job.executed = true;
    return new Promise((resolve, reject) => {
      const child = spawn(this.hdc.bin, args, { stdio: ['ignore', 'pipe', 'pipe'] });
      if (!allowCancelled) this.job.child = child;
      const out = [];
      const err = [];
      let timedOut = false;
      const timer = setTimeout(() => {
        timedOut = true;
        child.kill('SIGKILL');
      }, budget);
      let total = 0;
      let overflow = false;
      const take = (arr) => (d) => {
        if (overflow) return;
        total += d.length;
        if (total > MAX_OUTPUT_BYTES) {
          overflow = true;
          out.length = 0;
          err.length = 0;
          child.kill('SIGKILL');
          return;
        }
        arr.push(d);
      };
      child.stdout.on('data', take(out));
      child.stderr.on('data', take(err));
      child.on('error', (e) => {
        clearTimeout(timer);
        const missing = e.code === 'ENOENT' || e.code === 'EACCES';
        reject(
          new RelayError('internal', `hdc spawn failed: ${e.code ?? e.message}`, {
            executed: true,
            hint: missing ? `HDC_BIN is not runnable (${this.hdc.bin}); fix HDC_BIN or PATH in the launchd job, then run dsh-relay doctor` : undefined,
          }),
        );
      });
      child.on('close', (code, signal) => {
        clearTimeout(timer);
        if (!allowCancelled) this.job.child = null;
        if (this.job.cancelled && !allowCancelled) {
          return reject(new RelayError('cancelled', 'cancelled', { executed: true }));
        }
        if (overflow) {
          return reject(new RelayError('command_failed', 'hdc output exceeded 8 MB', { executed: true }));
        }
        if (timedOut) {
          return reject(new RelayError('device_timeout', 'device command timed out', { retryable: true, executed: true, hint: 'the command may or may not have run' }));
        }
        resolve({
          code,
          signal,
          stdout: Buffer.concat(out),
          stderr: Buffer.concat(err).toString('utf8'),
          text: Buffer.concat(out).toString('utf8'),
        });
      });
    });
  }

  dispose() {}
}
