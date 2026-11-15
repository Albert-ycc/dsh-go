// 十二个错误码与 HTTP 状态的对应见方案 4.6，Kotlin 侧按 code 映射文案。
export const HTTP_STATUS = {
  unauthorized: 401,
  forbidden_source: 403,
  cancelled: 409,
  device_locked: 409,
  bad_args: 422,
  halted: 423,
  command_failed: 424,
  busy: 429,
  device_offline: 503,
  device_unauthorized: 503,
  device_timeout: 504,
  internal: 500,
};

export class RelayError extends Error {
  /**
   * @param {keyof typeof HTTP_STATUS} code
   * @param {string} message
   * @param {{retryable?: boolean, hint?: string, executed?: boolean}} [opts]
   *   executed: 设备命令已经发出过，幂等缓存据此决定要不要保存这次结果
   */
  constructor(code, message, { retryable = false, hint, executed = false } = {}) {
    super(message);
    this.code = code;
    this.retryable = retryable;
    this.hint = hint;
    this.executed = executed;
  }
  get status() {
    return HTTP_STATUS[this.code] ?? 500;
  }
  toBody() {
    const error = { code: this.code, message: this.message, retryable: this.retryable };
    if (this.hint) error.hint = this.hint;
    return { ok: false, error };
  }
}

/** 启动期配置/环境错误：server.mjs 打印一行中文后以 exitCode（默认 78，EX_CONFIG）退出，不打印栈。 */
export class StartupError extends Error {
  constructor(message, exitCode = 78) {
    super(message);
    this.exitCode = exitCode;
  }
}
