// 设置 › 手机操控 › Mac 中继: the hand on 鸿蒙卓易通, where the container has
// no accessibility service and every screen read and tap goes through a relay
// on the person's Mac (docs/guide 手机操控 › 鸿蒙卓易通：经 Mac 中转).
//
// ```
//   Mac 中继
//   ╭─ ⓘ 已连接 ───────────────────────╮   ← one of nine states, each with its next step
//   ╰──────────────────────────────────╯
//   配对（没配过、或需要重新配对时）
//   ╭──────────────────────────────────╮
//   │ Mac 地址      192.0.2.10:8731 │
//   │ 配对码        ••••••••••           │   ← typed once, never stored or echoed
//   │ [ 配对 ]                          │
//   ╰──────────────────────────────────╯
//   当前设备       机型 / 连接方式 / 前台应用 / 最近一次成功
//   管理           测试连接 / 更新无线调试地址 / 停止中继 / 重新配对 / 忘记这台 Mac
// ```

import 'dart:async';

import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../state/phone_state.dart';
import '../common/brief_notice.dart';
import '../common/list_page_row.dart';
import '../features/feature_kit.dart';
import '../shell/page_frame.dart';
import '../theme/app_icons.dart';
import 'phone_widgets.dart';

extension PhoneRelayStateText on PhoneRelayState {
  String title(L10n l10n) => switch (this) {
    PhoneRelayState.ok => l10n.phoneRelayStateOk,
    PhoneRelayState.unreachable => l10n.phoneRelayStateUnreachable,
    PhoneRelayState.unauthorized => l10n.phoneRelayStateUnauthorized,
    PhoneRelayState.halted => l10n.phoneRelayStateHalted,
    PhoneRelayState.deviceOffline => l10n.phoneRelayStateDeviceOffline,
    PhoneRelayState.deviceUnauthorized => l10n.phoneRelayStateDeviceUnauthorized,
    PhoneRelayState.locked => l10n.phoneRelayStateLocked,
    PhoneRelayState.storeUnreadable => l10n.phoneRelayStateStoreUnreadable,
    PhoneRelayState.unconfigured => l10n.phoneRelayStateUnconfigured,
  };

  /// What is going on and the one next step.
  String hint(L10n l10n) => switch (this) {
    PhoneRelayState.ok => l10n.phoneRelayHintOk,
    PhoneRelayState.unreachable => l10n.phoneRelayHintUnreachable,
    PhoneRelayState.unauthorized => l10n.phoneRelayHintUnauthorized,
    PhoneRelayState.halted => l10n.phoneRelayHintHalted,
    PhoneRelayState.deviceOffline => l10n.phoneRelayHintDeviceOffline,
    PhoneRelayState.deviceUnauthorized => l10n.phoneRelayHintDeviceUnauthorized,
    PhoneRelayState.locked => l10n.phoneRelayHintLocked,
    PhoneRelayState.storeUnreadable => l10n.phoneRelayHintStoreUnreadable,
    PhoneRelayState.unconfigured => l10n.phoneRelayHintUnconfigured,
  };

  NoteTone get tone => switch (this) {
    PhoneRelayState.ok || PhoneRelayState.unconfigured || PhoneRelayState.locked => NoteTone.info,
    _ => NoteTone.warn,
  };
}

/// A failed relay call in words: the code's own sentence, else what the
/// native side said, else a plain failure.
String phoneRelayErrorText(L10n l10n, PhoneRelayResult r) => switch (r.code) {
  'bad_address' => l10n.phoneRelayErrorBadAddress,
  'unauthorized' => l10n.phoneRelayErrorUnauthorized,
  'pin_mismatch' => l10n.phoneRelayErrorPinMismatch,
  'relay_unreachable' => l10n.phoneRelayErrorRelayUnreachable,
  'forbidden_source' => l10n.phoneRelayErrorForbiddenSource,
  'device_locked' => l10n.phoneRelayErrorDeviceLocked,
  'halted' => l10n.phoneRelayErrorHalted,
  'device_offline' => l10n.phoneRelayErrorDeviceOffline,
  'device_unauthorized' => l10n.phoneRelayErrorDeviceUnauthorized,
  'device_timeout' => l10n.phoneRelayErrorDeviceTimeout,
  'busy' => l10n.phoneRelayErrorBusy,
  'unconfigured' => l10n.phoneRelayErrorUnconfigured,
  'store_failed' => l10n.phoneRelayErrorStoreFailed,
  'bad_args' => l10n.phoneRelayErrorBadArgs,
  'internal' => l10n.phoneRelayErrorInternal,
  _ => r.reason ?? l10n.phoneRelayErrorUnknown,
};

class PhoneRelayPage extends ConsumerStatefulWidget {
  const PhoneRelayPage({super.key});

  @override
  ConsumerState<PhoneRelayPage> createState() => _PhoneRelayPageState();
}

class _PhoneRelayPageState extends ConsumerState<PhoneRelayPage> {
  final _addr = TextEditingController();
  final _code = TextEditingController();
  bool _pairing = false;
  bool _testing = false;

  /// 重新配对 opened the form on an already paired phone.
  bool _repairing = false;

  /// Why the last pairing failed, under the form.
  String? _pairError;

  PhoneController get _phone => ref.read(phoneProvider.notifier);

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      if (mounted) unawaited(_phone.refreshStatus());
    });
  }

  @override
  void dispose() {
    _addr.dispose();
    _code.dispose();
    super.dispose();
  }

  void _toast(String text) {
    if (!mounted) return;
    ScaffoldMessenger.of(context)
      ..hideCurrentSnackBar()
      ..showSnackBar(SnackBar(content: Text(text)));
  }

  // 10 characters from the Mac's `dsh-relay pair`; dashes and spaces are cosmetic.
  bool get _canPair => _addr.text.trim().isNotEmpty && _code.text.replaceAll(RegExp(r'[\s-]'), '').length >= 10;

  Future<void> _pair() async {
    if (_pairing || !_canPair) return;
    final l10n = context.l10n;
    final code = _code.text;
    // Gone from the field before the call: whatever happens, it is not kept here.
    _code.clear();
    setState(() {
      _pairing = true;
      _pairError = null;
    });
    final result = await _phone.relayPair(_addr.text.trim(), code);
    if (!mounted) return;
    setState(() {
      _pairing = false;
      _pairError = result.ok ? null : phoneRelayErrorText(l10n, result);
      if (result.ok) _repairing = false;
    });
    if (result.ok) showBriefNotice(context, l10n.phoneRelayPaired);
  }

  Future<void> _test() async {
    if (_testing) return;
    final l10n = context.l10n;
    setState(() => _testing = true);
    final result = await _phone.relayTest();
    if (!mounted) return;
    setState(() => _testing = false);
    if (!result.ok) return _toast(phoneRelayErrorText(l10n, result));
    final model = result.device?.model;
    showBriefNotice(context, model == null ? l10n.phoneRelayTestOkNoModel : l10n.phoneRelayTestOk(model));
  }

  Future<void> _updateTarget() async {
    final l10n = context.l10n;
    final addr = await showDialog<String>(context: context, builder: (_) => const _TargetDialog());
    if (addr == null || !mounted) return;
    final result = await _phone.relaySetTarget(addr);
    if (!mounted) return;
    if (result.ok) {
      showBriefNotice(context, l10n.phoneRelayTargetDone);
    } else {
      _toast(phoneRelayErrorText(l10n, result));
    }
  }

  Future<void> _halt() async {
    final l10n = context.l10n;
    final result = await _phone.relayHalt();
    if (!mounted) return;
    if (result.ok) {
      showBriefNotice(context, l10n.phoneRelayHaltDone);
    } else {
      _toast(phoneRelayErrorText(l10n, result));
    }
  }

  Future<void> _forget() async {
    final l10n = context.l10n;
    final sure = await showDialog<bool>(
      context: context,
      builder: (dialog) => AlertDialog(
        title: Text(l10n.phoneRelayForgetTitle),
        content: Text(l10n.phoneRelayForgetBody),
        actions: [
          TextButton(onPressed: () => Navigator.pop(dialog, false), child: Text(l10n.commonCancel)),
          FilledButton(onPressed: () => Navigator.pop(dialog, true), child: Text(l10n.phoneRelayForgetConfirm)),
        ],
      ),
    );
    if (sure != true || !mounted) return;
    final ok = await _phone.relayForget();
    if (!mounted) return;
    setState(() => _repairing = false);
    if (ok) showBriefNotice(context, l10n.phoneRelayForgot);
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    final state = ref.watch(phoneProvider);
    final relay = state.status.relay;
    final ready = state.loaded && state.status.ready;
    final needsRepair = relay.state == PhoneRelayState.unauthorized || relay.state == PhoneRelayState.storeUnreadable;
    final showPair = !relay.configured || needsRepair || _repairing;

    return AppPage.list(
      title: l10n.phoneRelayTitle,
      subtitle: Text(l10n.phoneRelaySubtitle),
      children: [
        if (!state.loaded)
          const QuietSpinner(padding: EdgeInsets.symmetric(vertical: 32))
        else if (!ready)
          PhoneNote(tone: NoteTone.warn, title: l10n.phoneControlNotReady, text: l10n.phoneControlModuleNotReady)
        else ...[
          PhoneNote(tone: relay.state.tone, title: relay.state.title(l10n), text: relay.state.hint(l10n)),
          const SizedBox(height: 18),
          if (showPair) ...[_pairCard(l10n), const SizedBox(height: 24)],
          if (relay.configured) ...[_deviceCard(l10n, relay), const SizedBox(height: 24), _manageCard(l10n, relay)],
        ],
      ],
    );
  }

  Widget _pairCard(L10n l10n) => ListPageCard(
    title: l10n.phoneRelayPairTitle,
    children: [
      Padding(
        padding: const EdgeInsets.fromLTRB(16, 12, 16, 16),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Text(l10n.phoneRelayPairHint, style: Theme.of(context).textTheme.bodySmall?.copyWith(height: 1.5)),
            const SizedBox(height: 12),
            TextField(
              key: const ValueKey('relay-addr'),
              controller: _addr,
              keyboardType: TextInputType.url,
              autocorrect: false,
              enableSuggestions: false,
              enabled: !_pairing,
              decoration: InputDecoration(labelText: l10n.phoneRelayAddrLabel, helperText: l10n.phoneRelayAddrHelper),
              onChanged: (_) => setState(() {}),
            ),
            const SizedBox(height: 8),
            // The code is a credential: no echo, no suggestions or keyboard
            // learning, no selection (so no copy or paste), never logged.
            TextField(
              key: const ValueKey('relay-code'),
              controller: _code,
              obscureText: true,
              autocorrect: false,
              enableSuggestions: false,
              enableIMEPersonalizedLearning: false,
              enableInteractiveSelection: false,
              enabled: !_pairing,
              maxLength: 16,
              maxLengthEnforcement: MaxLengthEnforcement.enforced,
              inputFormatters: [FilteringTextInputFormatter.allow(RegExp(r'[A-Za-z0-9\- ]'))],
              decoration: InputDecoration(
                labelText: l10n.phoneRelayCodeLabel,
                helperText: l10n.phoneRelayCodeHelper,
                counterText: '',
              ),
              onChanged: (_) => setState(() {}),
            ),
            if (_pairError case final error?) ...[
              const SizedBox(height: 8),
              Text(
                error,
                key: const ValueKey('relay-pair-error'),
                style: Theme.of(context).textTheme.bodySmall?.copyWith(color: Theme.of(context).colorScheme.error),
              ),
            ],
            const SizedBox(height: 12),
            FilledButton(
              key: const ValueKey('relay-pair'),
              onPressed: _canPair && !_pairing ? _pair : null,
              child: Text(_pairing ? l10n.phoneRelayPairing : l10n.phoneRelayPair),
            ),
          ],
        ),
      ),
    ],
  );

  Widget _deviceCard(L10n l10n, PhoneRelay relay) {
    final device = relay.device;
    final unknown = l10n.phoneRelayDeviceUnknown;
    return ListPageCard(
      title: l10n.phoneRelayDeviceTitle,
      children: [
        ListPageRow(icon: AppIcons.computer, title: l10n.phoneRelayAddr, value: relay.addr ?? unknown),
        ListPageRow(icon: AppIcons.thisPhone, title: l10n.phoneRelayDeviceModel, value: device.model ?? unknown),
        ListPageRow(
          icon: AppIcons.link,
          title: l10n.phoneRelayDeviceVia,
          value: switch (device.viaTcp) {
            true => l10n.phoneRelayViaTcp,
            false => l10n.phoneRelayViaUsb,
            null => unknown,
          },
        ),
        ListPageRow(
          icon: AppIcons.readScreen,
          title: l10n.phoneRelayDeviceForeground,
          value: device.foreground ?? unknown,
        ),
        ListPageRow(
          icon: AppIcons.history,
          title: l10n.phoneRelayLastOk,
          value: relay.lastOkAt == null ? l10n.phoneRelayNever : _clock(relay.lastOkAt!),
        ),
      ],
    );
  }

  Widget _manageCard(L10n l10n, PhoneRelay relay) => ListPageCard(
    title: l10n.phoneRelayManageTitle,
    children: [
      ListPageRow(
        key: const ValueKey('relay-test'),
        icon: AppIcons.testConnection,
        title: l10n.phoneRelayTest,
        subtitle: _testing ? l10n.phoneRelayTesting : l10n.phoneRelayTestHint,
        trailing: _testing
            ? const SizedBox.square(
                dimension: 18,
                child: CircularProgressIndicator(strokeWidth: 2, strokeCap: StrokeCap.round),
              )
            : null,
        onTap: _testing ? null : _test,
      ),
      ListPageRow(
        key: const ValueKey('relay-target'),
        icon: AppIcons.edit,
        title: l10n.phoneRelayTarget,
        subtitle: l10n.phoneRelayTargetHint,
        subtitleMaxLines: 2,
        onTap: _updateTarget,
      ),
      ListPageRow(
        key: const ValueKey('relay-halt'),
        icon: AppIcons.stop,
        title: l10n.phoneRelayHalt,
        subtitle: l10n.phoneRelayHaltHint,
        subtitleMaxLines: 2,
        onTap: _halt,
      ),
      if (!_repairing && relay.state != PhoneRelayState.unauthorized && relay.state != PhoneRelayState.storeUnreadable)
        ListPageRow(
          key: const ValueKey('relay-repair'),
          icon: AppIcons.refresh,
          title: l10n.phoneRelayRepair,
          subtitle: l10n.phoneRelayRepairHint,
          onTap: () => setState(() => _repairing = true),
        ),
      ListPageRow(
        key: const ValueKey('relay-forget'),
        icon: AppIcons.delete,
        title: l10n.phoneRelayForget,
        subtitle: l10n.phoneRelayForgetHint,
        danger: true,
        onTap: _forget,
      ),
    ],
  );
}

String _two(int n) => n.toString().padLeft(2, '0');

/// Today: the time; another day: month-day and time.
String _clock(DateTime t) {
  final now = DateTime.now();
  final time = '${_two(t.hour)}:${_two(t.minute)}:${_two(t.second)}';
  final today = t.year == now.year && t.month == now.month && t.day == now.day;
  return today ? time : '${_two(t.month)}-${_two(t.day)} $time';
}

/// The phone's wireless-debugging address, when its port changed.
class _TargetDialog extends StatefulWidget {
  const _TargetDialog();

  @override
  State<_TargetDialog> createState() => _TargetDialogState();
}

class _TargetDialogState extends State<_TargetDialog> {
  final _addr = TextEditingController();

  @override
  void dispose() {
    _addr.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final l10n = context.l10n;
    return AlertDialog(
      title: Text(l10n.phoneRelayTarget),
      content: TextField(
        key: const ValueKey('relay-target-addr'),
        controller: _addr,
        autofocus: true,
        keyboardType: TextInputType.url,
        autocorrect: false,
        enableSuggestions: false,
        decoration: InputDecoration(labelText: l10n.phoneRelayTargetLabel, helperText: l10n.phoneRelayTargetHelper),
        onChanged: (_) => setState(() {}),
      ),
      actions: [
        TextButton(onPressed: () => Navigator.pop(context), child: Text(l10n.commonCancel)),
        FilledButton(
          onPressed: _addr.text.trim().isEmpty ? null : () => Navigator.pop(context, _addr.text.trim()),
          child: Text(l10n.phoneRelayUpdate),
        ),
      ],
    );
  }
}
