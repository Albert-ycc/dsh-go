// 卓易通's runtime has no git: a Git-sourced plugin install is refused up
// front with the reason, from any screen (every install goes through
// PluginInstallsController.install); npm specs and other engines are untouched.

import 'package:dsh_app/l10n/l10n.dart';
import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_app/state/connection_state.dart';
import 'package:dsh_app/state/hosts_state.dart';
import 'package:dsh_app/state/plugins_state.dart';
import 'package:dsh_app/state/read_list_identity.dart';
import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../phone/support.dart' show PhoneTestRuntime;
import '../state/read_list_refresh_test.dart' show FixedHost, SwitchConnection, switchConnectionProvider;
import 'support/fake_plugin_host.dart';

RuntimeStatus engine(String family) => RuntimeStatus(
  phase: RuntimePhase.running,
  dshVersion: '0.2.1',
  baseUrl: 'http://127.0.0.1:3080',
  token: 't',
  engineFamily: family,
);

Future<void> flush(ProviderContainer c) async {
  for (var i = 0; i < 5; i++) {
    await Future<void>.delayed(Duration.zero);
    await c.pump();
  }
}

void main() {
  final l10n = lookupL10n(const Locale('zh'));
  setUp(() => SharedPreferences.setMockInitialValues({}));

  test('which specs count as Git sources', () {
    for (final git in [
      'git+https://github.com/o/r.git#abc123',
      'git+ssh://git@github.com/o/r.git',
      'git://example.com/o/r.git',
      'git@github.com:o/r.git',
      'github:o/r',
      'o/r',
      'o/r#main',
      'https://example.com/o/r.git',
    ]) {
      expect(isGitPluginSpec(git), isTrue, reason: git);
    }
    for (final other in ['dsh-plugin-x@1.0.0', '@scope/pkg@2.1.0', 'dsh-plugin-x', 'https://example.com/pkg.tgz']) {
      expect(isGitPluginSpec(other), isFalse, reason: other);
    }
  });

  Future<(ProviderContainer, FakePluginCarrier)> setup(String family) async {
    final carrier = FakePluginCarrier();
    final client = DshClient.withCarrier(carrier);
    addTearDown(client.close);
    carrier.handlers['pluginManager/installBundle'] = (_) async => installResult(target: 'owner-plugin');
    carrier.handlers['pluginManager/listBundles'] = (_) => <Object?>[];
    final c = ProviderContainer(
      overrides: [
        activeHostProvider.overrideWith(FixedHost.new),
        switchConnectionProvider.overrideWith(() => SwitchConnection(fakeHost(client))),
        activeConnectionProvider.overrideWith((ref) => ref.watch(switchConnectionProvider)),
        readListIdentityProvider.overrideWithValue(null),
        localSafeModeProvider.overrideWithValue(false),
        localRuntimeProvider.overrideWith(
          () => PhoneTestRuntime(LocalRuntimeState(loaded: true, status: engine(family))),
        ),
      ],
    );
    addTearDown(c.dispose);
    c.listen(pluginInstallsProvider, (_, _) {});
    return (c, carrier);
  }

  test('on 卓易通 a Git spec is refused with the reason and never sent', () async {
    final (c, carrier) = await setup('bionic');
    final job = await c
        .read(pluginInstallsProvider.notifier)
        .install(const PluginInstallRequest(name: 'owner-plugin', spec: 'git+https://github.com/o/r.git#abc'));
    await flush(c);
    expect(job.phase, PluginInstallPhase.failed);
    expect(job.failure?.kind, PluginFailureKind.unsupported);
    expect(job.failure?.title, l10n.pluginsInstallGitRefusedBionic);
    expect(job.failure?.title, contains('没有 git'));
    expect(carrier.argsOf('pluginManager/installBundle'), isEmpty);
    expect(c.read(pluginInstallsProvider)['owner-plugin']?.phase, PluginInstallPhase.failed);
  });

  test('on 卓易通 an npm spec installs as before', () async {
    final (c, carrier) = await setup('bionic');
    final job = await c
        .read(pluginInstallsProvider.notifier)
        .install(const PluginInstallRequest(name: 'owner-plugin', spec: 'owner-plugin@1.0.0'));
    expect(job.phase, PluginInstallPhase.succeeded);
    expect(carrier.argsOf('pluginManager/installBundle'), hasLength(1));
  });

  test('on the usual runtime a Git spec is not refused here', () async {
    final (c, carrier) = await setup('glibc');
    await c
        .read(pluginInstallsProvider.notifier)
        .install(const PluginInstallRequest(name: 'owner-plugin', spec: 'git+https://github.com/o/r.git#abc'));
    expect(carrier.argsOf('pluginManager/installBundle'), hasLength(1));
  });
}
