// The app reads files straight from the engine's rootfs; which directory that
// is depends on the engine family, so every reader follows the bridge's
// `status.rootfs` instead of a fixed `runtime/rootfs`.

import 'dart:io';

import 'package:dsh_app/runtime/runtime_bridge.dart';
import 'package:dsh_app/runtime/runtime_state.dart';
import 'package:dsh_app/share_code/guest_fs.dart';
import 'package:dsh_app/state/read_list_identity.dart';
import 'package:dsh_app/state/voice_model_state.dart';
import 'package:dsh_app/ui_config/ui_config_state.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

class _Runtime extends LocalRuntimeController {
  _Runtime(this.initial);
  final RuntimeStatus initial;
  @override
  LocalRuntimeState build() => LocalRuntimeState(status: initial, loaded: true);

  void moveTo(String? rootfs) =>
      state = LocalRuntimeState(
        status: RuntimeStatus(phase: RuntimePhase.installed, installedVersion: 'v', rootfs: rootfs),
        loaded: true,
      );
}

void main() {
  late Directory a;
  late Directory b;
  setUp(() {
    a = Directory.systemTemp.createTempSync('rootfs-a');
    b = Directory.systemTemp.createTempSync('rootfs-b');
    File('${a.path}/root/.dsh/marker').createSync(recursive: true);
  });
  tearDown(() {
    a.deleteSync(recursive: true);
    b.deleteSync(recursive: true);
  });

  ProviderContainer make(String? rootfs) {
    final c = ProviderContainer(
      overrides: [
        localRuntimeProvider.overrideWith(
          () => _Runtime(RuntimeStatus(phase: RuntimePhase.installed, installedVersion: 'v', rootfs: rootfs)),
        ),
      ],
    );
    addTearDown(c.dispose);
    return c;
  }

  test('GuestFs.local takes the bridge path, and a missing directory is no rootfs', () async {
    expect((await GuestFs.local(rootfs: a.path))?.root.path, a.path);
    expect(await GuestFs.local(rootfs: '${a.path}/nope'), isNull);
  });

  test('guestFsProvider follows a rootfs that moves (family switch)', () async {
    final c = make(a.path);
    expect((await c.read(guestFsProvider.future))?.root.path, a.path);
    (c.read(localRuntimeProvider.notifier) as _Runtime).moveTo(b.path);
    expect((await c.read(guestFsProvider.future))?.root.path, b.path);
  });

  test('the UI-config resolver asks for the path at each call, not once', () async {
    final c = make(a.path);
    final resolve = c.read(uiRootfsResolverProvider);
    expect((await resolve())?.path, a.path);
    (c.read(localRuntimeProvider.notifier) as _Runtime).moveTo(b.path);
    expect((await resolve())?.path, b.path);
  });

  test('the read-list root is the bridge path, resolved', () async {
    final c = make(a.path);
    expect(await c.read(localReadListRootProvider.future), a.resolveSymbolicLinksSync());
    (c.read(localRuntimeProvider.notifier) as _Runtime).moveTo(b.path);
    expect(await c.read(localReadListRootProvider.future), b.resolveSymbolicLinksSync());
  });

  test('the voice model platform resolves files from the bridge path', () async {
    var path = a.path;
    final platform = DeviceVoiceModelPlatform(rootfs: () => path);
    expect((await platform.files())?.rootfs.path, a.path);
    path = b.path;
    expect((await platform.files())?.rootfs.path, b.path);
  });
}
