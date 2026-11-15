import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show PlatformException;
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../../l10n/l10n.dart';
import '../../runtime/runtime_bridge.dart';
import '../../runtime/runtime_state.dart';
import '../common/brief_notice.dart';
import '../theme/app_icons.dart';
import 'settings_widgets.dart';

/// 本机运行环境的三态选择与判定结果。改动只在引擎停止时可做，下次启动生效。
class EngineFamilySettings extends ConsumerStatefulWidget {
  const EngineFamilySettings({super.key});

  @override
  ConsumerState<EngineFamilySettings> createState() => _EngineFamilySettingsState();
}

class _EngineFamilySettingsState extends ConsumerState<EngineFamilySettings> {
  bool _saving = false;

  @override
  Widget build(BuildContext context) {
    final runtime = ref.watch(localRuntimeProvider);
    final status = runtime.status;
    final l10n = context.l10n;
    final stopped = !status.phase.isBusy && status.phase != RuntimePhase.running;
    final canChange = runtime.loaded && stopped && !_saving;

    String name(String family) => switch (family) {
      'glibc' => l10n.engineFamilyGlibcName,
      'bionic' => l10n.engineFamilyBionicName,
      _ => family,
    };
    final reason = status.engineFamilyReason;
    final showLimits = status.isBionic || status.engineFamilyOverride == 'bionic';

    Widget option(String value, String title, String hint, {bool available = true, String? missingHint}) {
      final selected = status.engineFamilyOverride == value;
      return Semantics(
        checked: selected,
        inMutuallyExclusiveGroup: true,
        child: SettingsTile(
          key: ValueKey('engine-family-$value'),
          title: title,
          subtitle: available ? hint : (missingHint ?? l10n.engineFamilyBionicMissing),
          trailing: SizedBox.square(
            dimension: 32,
            child: Icon(selected ? AppIcons.radioOn : AppIcons.radioOff, size: 24),
          ),
          onTap: canChange && available && !selected ? () => _change(value) : null,
        ),
      );
    }

    return SettingsSection(
      title: l10n.engineFamilyTitle,
      footer: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        spacing: 6,
        children: [
          Text(l10n.engineFamilyIndependentNote, key: const ValueKey('engine-family-note')),
          if (!stopped) Text(l10n.engineFamilyStopFirst),
          if (showLimits) Text(l10n.phoneBionicLimits, key: const ValueKey('engine-family-limits')),
        ],
      ),
      children: [
        option('auto', l10n.engineFamilyAuto, l10n.engineFamilyAutoHint),
        option(
          'glibc',
          l10n.engineFamilyForceGlibc,
          l10n.engineFamilyGlibcHint,
          available: status.glibcBundled || status.engineFamilyOverride == 'glibc',
          missingHint: l10n.engineFamilyGlibcMissing,
        ),
        option(
          'bionic',
          l10n.engineFamilyForceBionic,
          l10n.engineFamilyBionicHint,
          available: status.engineFamilyAvailable || status.engineFamilyOverride == 'bionic',
        ),
        InfoRow(label: l10n.engineFamilyCurrent, value: name(status.engineFamily)),
        if (reason != null)
          InfoRow(
            label: l10n.engineFamilyReasonLabel,
            value: switch (reason) {
              'override' => l10n.engineFamilyReasonOverride,
              'fallback' => l10n.engineFamilyReasonFallback,
              'seLinux' => l10n.engineFamilyReasonSeLinux,
              'syscallProbe' => l10n.engineFamilyReasonProbe,
              'default' => l10n.engineFamilyReasonDefault,
              _ => reason,
            },
          ),
      ],
    );
  }

  Future<void> _change(String family) async {
    setState(() => _saving = true);
    try {
      await ref.read(runtimeBridgeProvider).setEngineFamily(family);
    } catch (e) {
      // The native side says why (e.g. the engine is running); keep its words.
      final why = e is PlatformException ? e.message : null;
      if (mounted) {
        final base = context.l10n.engineFamilyChangeFailed;
        showBriefNotice(context, why == null || why.isEmpty ? base : '$base $why');
      }
    } finally {
      if (mounted) setState(() => _saving = false);
    }
  }
}
