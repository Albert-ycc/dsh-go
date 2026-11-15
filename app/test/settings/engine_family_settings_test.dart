import 'package:dsh_app/diagnostics/diagnostics_bundle.dart';
import 'package:dsh_app/diagnostics/diagnostics_channel.dart';
import 'package:dsh_app/l10n/l10n.dart';
import 'package:dsh_app/runtime/local_footprint.dart';
import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_app/state/onboarding_state.dart';
import 'package:dsh_app/state/connection_state.dart';
import 'package:dsh_app/ui/access/access_pick_page.dart';
import 'package:dsh_app/ui/settings/direct_engine_settings.dart';
import 'package:dsh_app/ui/settings/engine_family_settings.dart';
import 'package:dsh_app/ui/settings/settings_widgets.dart';
import 'package:dsh_app/ui/settings/voice_settings_page.dart';
import 'package:dsh_app/ui/theme/theme.dart';
import 'package:dsh_app/state/access_state.dart';
import 'package:dsh_app/state/settings_state.dart';
import 'package:dsh_app/state/voice_settings.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart' show PlatformException;
import 'package:flutter_localizations/flutter_localizations.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_riverpod/misc.dart' show Override;
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../access/support.dart';
import '../chat/support/harness.dart';
import '../shell/golden_harness.dart';
import '../support/test_fonts.dart';

class _Runtime extends LocalRuntimeController {
  _Runtime(this.initial);
  final RuntimeStatus initial;
  @override
  LocalRuntimeState build() => LocalRuntimeState(status: initial, loaded: true);
}

class _Bridge extends RuntimeBridge {
  final families = <String>[];
  bool fail = false;
  Object? failWith;
  @override
  Future<void> setEngineFamily(String family) async {
    families.add(family);
    if (failWith != null) throw failWith!;
    if (fail) throw StateError('native rejected transition');
  }
}

class _Channel extends DiagnosticsChannel {
  const _Channel(this.manifest);
  final Map<String, Object?> manifest;
  @override
  Future<NativeDiagnostics> info() async => NativeDiagnostics(manifest: manifest);
}

void main() {
  setUpAll(loadTestFonts);
  setUp(() => SharedPreferences.setMockInitialValues({}));
  final l10n = lookupL10n(AppLocale.zhCN);

  List<Override> runtime(RuntimeStatus status, [_Bridge? bridge]) => [
    localRuntimeProvider.overrideWith(() => _Runtime(status)),
    runtimeBridgeProvider.overrideWithValue(bridge ?? _Bridge()),
  ];

  const stoppedGlibc = RuntimeStatus(
    phase: RuntimePhase.installed,
    engineFamily: 'glibc',
    engineFamilyAvailable: true,
    engineFamilyReason: 'default',
  );
  const stoppedBionic = RuntimeStatus(
    phase: RuntimePhase.installed,
    engineFamily: 'bionic',
    engineFamilyAvailable: true,
    engineFamilyReason: 'fallback',
  );

  group('RuntimeStatus', () {
    test('an older native side leaves the family at glibc and the bionic choice unavailable', () {
      final status = RuntimeStatus.fromMap({'phase': 'installed'});
      expect(status.engineFamily, 'glibc');
      expect(status.engineFamilyAvailable, isFalse);
      expect(status.engineFamilyOverride, 'auto');
      expect(status.engineFamilyReason, isNull);
      expect(status.isBionic, isFalse);
    });

    test('rootfs and glibcBundled are read; an older native side keeps glibc available', () {
      final status = RuntimeStatus.fromMap({'phase': 'installed', 'rootfs': '/data/user/0/app/files/runtime-bionic/rootfs', 'glibcBundled': false});
      expect(status.rootfs, '/data/user/0/app/files/runtime-bionic/rootfs');
      expect(status.glibcBundled, isFalse);
      final old = RuntimeStatus.fromMap({'phase': 'installed'});
      expect(old.rootfs, isNull);
      expect(old.glibcBundled, isTrue);
    });

    test('the four fields are read and travel in the launch diagnostics', () {
      final status = RuntimeStatus.fromMap({
        'phase': 'installed',
        'engineFamily': 'bionic',
        'engineFamilyAvailable': true,
        'engineFamilyOverride': 'auto',
        'engineFamilyReason': 'seLinux',
      });
      expect(status.isBionic, isTrue);
      expect(status.launchDiagnostics, containsPair('engineFamily', 'bionic'));
      expect(status.launchDiagnostics, containsPair('engineFamilyAvailable', true));
      expect(status.launchDiagnostics, containsPair('engineFamilyOverride', 'auto'));
      expect(status.launchDiagnostics, containsPair('engineFamilyReason', 'seLinux'));
      final summary = DiagnosticsBundle.summary(
        DiagnosticsSnapshot(at: DateTime.utc(2026), status: status, settings: const AppSettings()),
      )['runtime'];
      expect(summary, containsPair('engineFamily', 'bionic'));
      expect(summary, containsPair('engineFamilyReason', 'seLinux'));
    });
  });

  group('EngineFamilySettings', () {
    Future<void> show(WidgetTester tester, RuntimeStatus status, _Bridge bridge) async {
      tester.view.physicalSize = const Size(320, 900);
      tester.view.devicePixelRatio = 1;
      addTearDown(tester.view.reset);
      await tester.pumpWidget(
        testApp(
          const Scaffold(body: SingleChildScrollView(child: EngineFamilySettings())),
          overrides: runtime(status, bridge),
        ),
      );
      await tester.pumpAndSettle();
    }

    testWidgets('picking a family asks native once; the saved choice is the checked one', (tester) async {
      final bridge = _Bridge();
      await show(tester, stoppedGlibc, bridge);
      expect(find.text(l10n.engineFamilyGlibcName), findsOneWidget);
      expect(find.text(l10n.engineFamilyIndependentNote), findsOneWidget);
      expect(find.text(l10n.engineFamilyReasonDefault), findsOneWidget);
      await tester.tap(find.byKey(const ValueKey('engine-family-bionic')));
      await tester.pumpAndSettle();
      expect(bridge.families, ['bionic']);
      // 当前已选的一项再点不重复提交。
      await tester.tap(find.byKey(const ValueKey('engine-family-auto')));
      await tester.pumpAndSettle();
      expect(bridge.families, ['bionic']);
    });

    testWidgets('a rejected change says how to retry', (tester) async {
      final bridge = _Bridge()..fail = true;
      await show(tester, stoppedGlibc, bridge);
      await tester.tap(find.byKey(const ValueKey('engine-family-glibc')));
      await tester.pump();
      expect(find.text(l10n.engineFamilyChangeFailed), findsOneWidget);
      await tester.pumpAndSettle(const Duration(seconds: 4));
    });

    testWidgets('no glibc package: forcing glibc is greyed with the reason, auto stays', (tester) async {
      await show(
        tester,
        const RuntimeStatus(phase: RuntimePhase.installed, engineFamilyAvailable: true, glibcBundled: false),
        _Bridge(),
      );
      final glibc = tester.widget<SettingsTile>(find.byKey(const ValueKey('engine-family-glibc')));
      expect(glibc.onTap, isNull);
      expect(glibc.subtitle, l10n.engineFamilyGlibcMissing);
      // 已经选着 auto，点它不重复提交；换成 bionic 时 auto 仍可点。
      expect(tester.widget<SettingsTile>(find.byKey(const ValueKey('engine-family-bionic'))).onTap, isNotNull);
    });

    testWidgets('a rejection from native keeps its words', (tester) async {
      final bridge = _Bridge()..failWith = PlatformException(code: 'x', message: 'stop the engine first');
      await show(tester, stoppedGlibc, bridge);
      await tester.tap(find.byKey(const ValueKey('engine-family-bionic')));
      await tester.pump();
      expect(find.textContaining('stop the engine first'), findsOneWidget);
      await tester.pumpAndSettle(const Duration(seconds: 4));
    });

    testWidgets('while the engine runs nothing can be picked and the footer says why', (tester) async {
      final bridge = _Bridge();
      await show(tester, const RuntimeStatus(phase: RuntimePhase.running, engineFamilyAvailable: true), bridge);
      for (final family in ['auto', 'glibc', 'bionic']) {
        final tile = tester.widget<SettingsTile>(find.byKey(ValueKey('engine-family-$family')));
        expect(tile.onTap, isNull, reason: family);
      }
      expect(find.text(l10n.engineFamilyStopFirst), findsOneWidget);
    });

    testWidgets('no bionic package: that choice is greyed with the reason', (tester) async {
      await show(tester, const RuntimeStatus(phase: RuntimePhase.installed), _Bridge());
      final tile = tester.widget<SettingsTile>(find.byKey(const ValueKey('engine-family-bionic')));
      expect(tile.onTap, isNull);
      expect(tile.subtitle, l10n.engineFamilyBionicMissing);
    });

    testWidgets('after an automatic fallback: bionic is shown with the fallback reason and what it lacks', (
      tester,
    ) async {
      await show(tester, stoppedBionic, _Bridge());
      expect(find.text(l10n.engineFamilyBionicName), findsOneWidget);
      expect(find.text(l10n.engineFamilyReasonFallback), findsOneWidget);
      expect(find.byKey(const ValueKey('engine-family-limits')), findsOneWidget);
      expect(l10n.phoneBionicLimits, contains('git'));
      expect(l10n.phoneBionicLimits, contains('python'));
    });

    testWidgets('an unknown reason code is shown as it is', (tester) async {
      await show(tester, const RuntimeStatus(phase: RuntimePhase.installed, engineFamilyReason: 'newCode'), _Bridge());
      expect(find.text('newCode'), findsOneWidget);
    });
  });

  group('bionic hides what it cannot run', () {
    for (final (name, status, expected) in [('bionic', stoppedBionic, 0), ('glibc', stoppedGlibc, 1)]) {
      testWidgets('the direct-launch switch under $name: $expected', (tester) async {
        await tester.pumpWidget(
          testApp(
            const Scaffold(body: SingleChildScrollView(child: DirectEngineSettings())),
            overrides: runtime(status),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.byType(SettingsSwitchTile), findsNWidgets(expected));
      });
    }

    testWidgets('voice settings: no engine route and no model card, with the reason instead', (tester) async {
      await tester.pumpWidget(testApp(const VoiceSettingsPage(), overrides: runtime(stoppedBionic)));
      await tester.pumpAndSettle();
      expect(find.byKey(const ValueKey('voice-mode-engine')), findsNothing);
      expect(find.byKey(const ValueKey('voice-mode-system')), findsOneWidget);
      expect(find.byKey(const ValueKey('voice-model-card')), findsNothing);
      expect(find.text(l10n.phoneBionicVoiceUnavailable), findsOneWidget);
    });

    testWidgets('voice settings under glibc are unchanged', (tester) async {
      await tester.pumpWidget(testApp(const VoiceSettingsPage(), overrides: runtime(stoppedGlibc)));
      await tester.pumpAndSettle();
      expect(find.byKey(ValueKey('voice-mode-${VoiceMode.engine.name}')), findsOneWidget);
      expect(find.byKey(const ValueKey('voice-model-card')), findsOneWidget);
    });

    for (final (name, status, expected) in [('bionic', stoppedBionic, 0), ('glibc', stoppedGlibc, 1)]) {
      testWidgets('first-run access list under $name offers voice input: $expected', (tester) async {
        await tester.pumpWidget(
          ProviderScope(
            overrides: [
              onboardingPrefsProvider.overrideWith(FakeOnboardingPrefs.new),
              localConnectionProvider.overrideWith(FakeConnection.new),
              ...accessOverrides(FakeAccessDriver(offPhone)),
              ...runtime(status),
            ],
            child: MaterialApp(
              theme: withTestFonts(buildLightTheme()),
              locale: AppLocale.zhCN,
              supportedLocales: AppLocale.supported,
              localizationsDelegates: const [L10n.delegate, ...GlobalMaterialLocalizations.delegates],
              home: AccessPickPage(onPicked: (_, _) {}, onSkip: () {}),
            ),
          ),
        );
        await tester.pumpAndSettle();
        expect(find.byKey(ValueKey('access-feature-${AccessFeature.voice.name}')), findsNWidgets(expected));
        expect(find.byKey(ValueKey('access-feature-${AccessFeature.files.name}')), findsOneWidget);
      });
    }
  });

  test('the install card reads the size from whichever manifest native hands over (bionic: rootfsUncompressedBytes, not the node-inclusive total)', () async {
    final container = ProviderContainer(
      overrides: [
        diagnosticsChannelProvider.overrideWithValue(
          const _Channel({'family': 'bionic', 'rootfsUncompressedBytes': 186282707, 'uncompressedBytes': 252567779}),
        ),
      ],
    );
    addTearDown(container.dispose);
    final footprint = await container.read(localFootprintProvider.future);
    expect(footprint.unpackedMb, 178);
    // 安装器口径：解包体积 +30%。
    expect(footprint.requiredStorageMb, 232);
  });
}
