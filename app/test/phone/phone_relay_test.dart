// 设置 › 手机操控 › Mac 中继 (卓易通): the status block's parsing, the page in
// each of the eight relay states, pairing (the code never shown, kept or
// echoed), the page's actions, and the phone-control page on the Mac hand.

import 'package:dsh_app/l10n/l10n.dart';
import 'package:dsh_app/state/phone_state.dart';
import 'package:dsh_app/ui/phone/phone_control_screen.dart';
import 'package:dsh_app/ui/phone/phone_relay_page.dart';
import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:shared_preferences/shared_preferences.dart';

import 'harness.dart';
import 'support.dart';

const _wire = {
  'ok': PhoneRelayState.ok,
  'unreachable': PhoneRelayState.unreachable,
  'unauthorized': PhoneRelayState.unauthorized,
  'halted': PhoneRelayState.halted,
  'device_offline': PhoneRelayState.deviceOffline,
  'device_unauthorized': PhoneRelayState.deviceUnauthorized,
  'locked': PhoneRelayState.locked,
  'store_unreadable': PhoneRelayState.storeUnreadable,
  'unconfigured': PhoneRelayState.unconfigured,
};

const _code = 'K7M2Q9XA4B';

FakePhoneNative macNative({
  String state = 'ok',
  bool configured = true,
  Map<String, Object?>? device = const {'model': 'VDE-AL00', 'via': 'usb', 'foreground': 'com.huawei.hmos.settings'},
  int? lastOkAt,
}) => FakePhoneNative(
  status: phoneStatus(
    hand: 'mac',
    relay: relayBlock(
      state: state,
      configured: configured,
      addr: configured ? '192.0.2.10:8731' : null,
      device: device,
      lastOkAt: lastOkAt,
    ),
  ),
);

Future<void> enterPairing(WidgetTester tester, {String addr = '192.0.2.10:8731', String code = _code}) async {
  await tester.enterText(find.byKey(const ValueKey('relay-addr')), addr);
  await tester.enterText(find.byKey(const ValueKey('relay-code')), code);
  await tester.pump();
}

TextField field(WidgetTester tester, String key) => tester.widget<TextField>(find.byKey(ValueKey(key)));

void main() {
  final l10n = lookupL10n(const Locale('zh'));
  setUp(() => SharedPreferences.setMockInitialValues({}));

  group('PhoneStatus', () {
    test('an Android status has no relay: a11y hand, nothing paired', () {
      final s = PhoneStatus.fromMap(phoneStatus());
      expect(s.hand, PhoneHand.a11y);
      expect(s.handSetting, 'auto');
      expect(s.relay.configured, isFalse);
      expect(s.relay.state, PhoneRelayState.unconfigured);
    });

    test('the relay block is read whole', () {
      final s = PhoneStatus.fromMap({
        ...phoneStatus(
          hand: 'mac',
          relay: relayBlock(state: 'device_offline', lastOkAt: 1793580000000),
        ),
        'handSetting': 'auto',
      });
      expect(s.hand, PhoneHand.mac);
      expect(s.relay.configured, isTrue);
      expect(s.relay.addr, '192.0.2.10:8731');
      expect(s.relay.state, PhoneRelayState.deviceOffline);
      expect(s.relay.device.model, 'VDE-AL00');
      expect(s.relay.device.viaTcp, isFalse);
      expect(s.relay.device.foreground, 'com.huawei.hmos.settings');
      expect(s.relay.lastOkAt, DateTime.fromMillisecondsSinceEpoch(1793580000000));
    });

    test('all eight wire states map, and an unknown one reads as unpaired', () {
      for (final e in _wire.entries) {
        expect(
          PhoneStatus.fromMap(phoneStatus(relay: relayBlock(state: e.key))).relay.state,
          e.value,
          reason: e.key,
        );
      }
      expect(
        PhoneStatus.fromMap(phoneStatus(relay: relayBlock(state: 'new_state'))).relay.state,
        PhoneRelayState.unconfigured,
      );
      expect(_wire.values.toSet(), PhoneRelayState.values.toSet());
    });

    test('via tcp is the wireless channel', () {
      final s = PhoneStatus.fromMap(phoneStatus(relay: relayBlock(device: const {'model': 'X', 'via': 'tcp'})));
      expect(s.relay.device.viaTcp, isTrue);
    });
  });

  group('store_unreadable', () {
    test('is read from the wire, not taken for unconfigured', () {
      expect(PhoneRelayState.parse('store_unreadable'), PhoneRelayState.storeUnreadable);
    });

    testWidgets('says the pairing is void and offers the form, without 重新配对', (tester) async {
      await pumpPhone(tester, const PhoneRelayPage(), native: macNative(state: 'store_unreadable'));
      expect(find.text(l10n.phoneRelayStateStoreUnreadable), findsOneWidget);
      expect(l10n.phoneRelayStateStoreUnreadable, '配对已失效，请重新配对');
      expect(find.byKey(const ValueKey('relay-pair')), findsOneWidget);
      expect(find.byKey(const ValueKey('relay-repair')), findsNothing);
    });

    test('new codes have their own sentences', () {
      for (final code in ['store_failed', 'bad_args', 'internal']) {
        final text = phoneRelayErrorText(l10n, PhoneRelayResult(ok: false, code: code, reason: 'native text'));
        expect(text, isNot('native text'), reason: code);
        expect(text, isNot(l10n.phoneRelayErrorUnknown), reason: code);
      }
    });
  });

  group('the page in each state', () {
    for (final e in _wire.entries) {
      testWidgets(e.key, (tester) async {
        final configured = e.key != 'unconfigured';
        await pumpPhone(
          tester,
          const PhoneRelayPage(),
          native: macNative(state: e.key, configured: configured),
        );
        expect(find.text(e.value.title(l10n)), findsOneWidget);
        expect(find.text(e.value.hint(l10n)), findsOneWidget);
        // Pairing is offered when nothing is paired or the pairing was refused.
        final pairing = e.key == 'unconfigured' || e.key == 'unauthorized' || e.key == 'store_unreadable';
        expect(find.byKey(const ValueKey('relay-pair')), pairing ? findsOneWidget : findsNothing);
        // The phone's own details and actions exist once something is paired.
        expect(find.byKey(const ValueKey('relay-test')), configured ? findsOneWidget : findsNothing);
        expect(find.byKey(const ValueKey('relay-halt')), configured ? findsOneWidget : findsNothing);
        expect(find.byKey(const ValueKey('relay-forget')), configured ? findsOneWidget : findsNothing);
      });
    }

    testWidgets('a connected phone shows model, channel, app in front and last success', (tester) async {
      final at = DateTime.now().subtract(const Duration(minutes: 3));
      await pumpPhone(tester, const PhoneRelayPage(), native: macNative(lastOkAt: at.millisecondsSinceEpoch));
      expect(find.text('192.0.2.10:8731'), findsOneWidget);
      expect(find.text('VDE-AL00'), findsOneWidget);
      expect(find.text('USB'), findsOneWidget);
      expect(find.text('com.huawei.hmos.settings'), findsOneWidget);
      expect(find.text(l10n.phoneRelayNever), findsNothing);
    });

    testWidgets('a wireless channel and a missing success are said plainly', (tester) async {
      await pumpPhone(
        tester,
        const PhoneRelayPage(),
        native: macNative(state: 'device_offline', device: const {'model': 'VDE-AL00', 'via': 'tcp'}),
      );
      expect(find.text(l10n.phoneRelayViaTcp), findsOneWidget);
      expect(find.text(l10n.phoneRelayNever), findsOneWidget);
    });

    testWidgets('without the native module the page says so', (tester) async {
      await pumpPhone(tester, const PhoneRelayPage(), missingNative: true);
      expect(find.text(l10n.phoneControlModuleNotReady), findsOneWidget);
      expect(find.byKey(const ValueKey('relay-pair')), findsNothing);
    });
  });

  group('pairing', () {
    testWidgets('the code field is a secret: obscured, no suggestions, no selection, no copy', (tester) async {
      await pumpPhone(tester, const PhoneRelayPage(), native: macNative(configured: false, state: 'unconfigured'));
      final code = field(tester, 'relay-code');
      expect(code.obscureText, isTrue);
      expect(code.enableSuggestions, isFalse);
      expect(code.autocorrect, isFalse);
      expect(code.enableIMEPersonalizedLearning, isFalse);
      expect(code.enableInteractiveSelection, isFalse);
      expect(field(tester, 'relay-addr').obscureText, isFalse);
    });

    testWidgets('pairing needs an address and a whole code', (tester) async {
      await pumpPhone(tester, const PhoneRelayPage(), native: macNative(configured: false, state: 'unconfigured'));
      FilledButton button() => tester.widget<FilledButton>(find.byKey(const ValueKey('relay-pair')));
      expect(button().onPressed, isNull);
      await enterPairing(tester, code: 'K7M2');
      expect(button().onPressed, isNull);
      await enterPairing(tester);
      expect(button().onPressed, isNotNull);
      await enterPairing(tester, code: 'K7M2Q-9XA4B');
      expect(button().onPressed, isNotNull);
    });

    testWidgets('a good code pairs: sent once, gone from the field, page now connected', (tester) async {
      final h = await pumpPhone(
        tester,
        const PhoneRelayPage(),
        native: macNative(configured: false, state: 'unconfigured'),
      );
      await enterPairing(tester);
      await tester.tap(find.byKey(const ValueKey('relay-pair')));
      await settlePhone(tester);
      final calls = h.native!.callsOf('relayPair').toList();
      expect(calls, hasLength(1));
      expect(calls.single.arguments, {'addr': '192.0.2.10:8731', 'code': _code});
      expect(find.byKey(const ValueKey('relay-pair')), findsNothing);
      expect(find.text(PhoneRelayState.ok.title(l10n)), findsOneWidget);
      expect(find.text(_code), findsNothing);
      // The status was read again afterwards.
      expect(h.native!.callsOf('status'), isNotEmpty);
    });

    for (final c in [
      'unauthorized',
      'pin_mismatch',
      'relay_unreachable',
      'bad_address',
      'store_failed',
      'bad_args',
      'internal',
    ]) {
      testWidgets('$c: its sentence under the form, the code cleared and shown nowhere', (tester) async {
        final native = macNative(configured: false, state: 'unconfigured')
          ..results['relayPair'] = {'ok': false, 'code': c, 'reason': 'native text'};
        await pumpPhone(tester, const PhoneRelayPage(), native: native);
        await enterPairing(tester);
        await tester.tap(find.byKey(const ValueKey('relay-pair')));
        await settlePhone(tester);
        final shown = tester.widget<Text>(find.byKey(const ValueKey('relay-pair-error'))).data!;
        expect(shown, isNot('native text'));
        expect(shown, phoneRelayErrorText(l10n, PhoneRelayResult(ok: false, code: c)));
        expect(
          tester
              .widget<EditableText>(
                find.descendant(of: find.byKey(const ValueKey('relay-code')), matching: find.byType(EditableText)),
              )
              .controller
              .text,
          isEmpty,
        );
        expect(find.textContaining(_code), findsNothing);
        // The address stays so another code can be tried.
        expect(find.text('192.0.2.10:8731'), findsOneWidget);
      });
    }

    testWidgets('an unknown error code shows what the native side said', (tester) async {
      final native = macNative(configured: false, state: 'unconfigured')
        ..results['relayPair'] = {'ok': false, 'code': 'strange', 'reason': '原生给的话'};
      await pumpPhone(tester, const PhoneRelayPage(), native: native);
      await enterPairing(tester);
      await tester.tap(find.byKey(const ValueKey('relay-pair')));
      await settlePhone(tester);
      expect(find.text('原生给的话'), findsOneWidget);
    });

    testWidgets('a refused pairing offers the form again; 重新配对 opens it on a good one', (tester) async {
      await pumpPhone(tester, const PhoneRelayPage(), native: macNative());
      expect(find.byKey(const ValueKey('relay-pair')), findsNothing);
      await tester.tap(find.byKey(const ValueKey('relay-repair')));
      await settlePhone(tester);
      expect(find.byKey(const ValueKey('relay-pair')), findsOneWidget);
    });
  });

  group('actions', () {
    testWidgets('测试连接 asks the native side once and says the model', (tester) async {
      final h = await pumpPhone(tester, const PhoneRelayPage(), native: macNative());
      await tester.tap(find.byKey(const ValueKey('relay-test')));
      await settlePhone(tester);
      expect(h.native!.callsOf('relayTest'), hasLength(1));
      expect(find.text(l10n.phoneRelayTestOk('VDE-AL00')), findsOneWidget);
    });

    testWidgets('测试连接 that fails says why, by code', (tester) async {
      final native = macNative(state: 'device_offline')
        ..results['relayTest'] = {'ok': false, 'code': 'device_offline', 'reason': 'x', 'state': 'device_offline'};
      await pumpPhone(tester, const PhoneRelayPage(), native: native);
      await tester.tap(find.byKey(const ValueKey('relay-test')));
      await settlePhone(tester);
      expect(find.text(l10n.phoneRelayErrorDeviceOffline), findsOneWidget);
    });

    testWidgets('更新无线调试地址 sends the new ip:port', (tester) async {
      final h = await pumpPhone(tester, const PhoneRelayPage(), native: macNative(state: 'device_offline'));
      await tester.tap(find.byKey(const ValueKey('relay-target')));
      await tester.pumpAndSettle();
      await tester.enterText(find.byKey(const ValueKey('relay-target-addr')), '192.0.2.20:46225');
      await tester.pump();
      await tester.tap(find.text(l10n.phoneRelayUpdate));
      await settlePhone(tester);
      expect(h.native!.callsOf('relaySetTarget').single.arguments, {'addr': '192.0.2.20:46225'});
    });

    testWidgets('停止中继 halts at once, no question asked, and the page shows 已暂停', (tester) async {
      final h = await pumpPhone(tester, const PhoneRelayPage(), native: macNative());
      await tester.tap(find.byKey(const ValueKey('relay-halt')));
      await settlePhone(tester);
      expect(h.native!.callsOf('relayHalt'), hasLength(1));
      expect(find.text(PhoneRelayState.halted.hint(l10n)), findsOneWidget);
    });

    testWidgets('忘记这台 Mac asks first, and says the Mac keeps its entry', (tester) async {
      final h = await pumpPhone(tester, const PhoneRelayPage(), native: macNative());
      await tester.tap(find.byKey(const ValueKey('relay-forget')));
      await tester.pumpAndSettle();
      expect(find.textContaining('dsh-relay revoke'), findsOneWidget);
      expect(h.native!.callsOf('relayForget'), isEmpty);
      await tester.tap(find.text(l10n.commonCancel));
      await tester.pumpAndSettle();
      expect(h.native!.callsOf('relayForget'), isEmpty);
      await tester.tap(find.byKey(const ValueKey('relay-forget')));
      await tester.pumpAndSettle();
      await tester.tap(find.text(l10n.phoneRelayForgetConfirm));
      await settlePhone(tester);
      expect(h.native!.callsOf('relayForget'), hasLength(1));
      expect(find.byKey(const ValueKey('relay-pair')), findsOneWidget);
    });
  });

  group('the phone-control page', () {
    testWidgets('on the Mac hand the first step is the relay, not accessibility', (tester) async {
      await pumpPhone(tester, const PhoneControlScreen(), native: macNative(state: 'unconfigured', configured: false));
      expect(find.text(l10n.phoneControlAccessibilityService), findsNothing);
      expect(find.byKey(const Key('phone-a11y-restore')), findsNothing);
      expect(find.text(l10n.phoneFloatingBar), findsNothing);
      expect(find.text(l10n.phoneDndTitle), findsNothing);
      expect(find.text(l10n.phoneRelayStepGoPair), findsOneWidget);
      expect(find.text(l10n.phoneRelayStatusLine(PhoneRelayState.unconfigured.title(l10n))), findsOneWidget);
      await tester.tap(find.text(l10n.phoneRelayStepGoPair));
      await tester.pumpAndSettle();
      expect(find.byType(PhoneRelayPage), findsOneWidget);
    });

    testWidgets('a connected relay is a finished step and unlocks 读一下当前屏幕', (tester) async {
      await pumpPhone(tester, const PhoneControlScreen(), native: macNative());
      expect(find.text(l10n.phoneRelayStepOk('VDE-AL00')), findsOneWidget);
      expect(find.text(l10n.phoneControlReady), findsOneWidget);
      expect(find.text(l10n.phoneControlReadScreenHint), findsOneWidget);
    });

    testWidgets('a relay that is not connected keeps 读一下当前屏幕 closed and says what to do', (tester) async {
      await pumpPhone(tester, const PhoneControlScreen(), native: macNative(state: 'halted'));
      expect(find.text(l10n.phoneRelayReadScreenFirst), findsOneWidget);
      expect(find.text(l10n.phoneRelayStepGoFix), findsOneWidget);
    });

    testWidgets('the Android hand is the page it was: accessibility step, no relay step', (tester) async {
      await pumpPhone(tester, const PhoneControlScreen(), native: FakePhoneNative());
      expect(find.text(l10n.phoneControlAccessibilityService), findsOneWidget);
      expect(find.text(l10n.phoneRelayStepGoPair), findsNothing);
      expect(find.text(l10n.phoneFloatingBar), findsOneWidget);
      // The relay page is still one row away, with its one-line explanation.
      expect(find.text(l10n.phoneRelayRowHint), findsOneWidget);
    });

    testWidgets('the 进阶 row opens the relay page', (tester) async {
      await pumpPhone(tester, const PhoneControlScreen(), native: macNative());
      await tester.tap(find.text(l10n.phoneRelayTitle).last);
      await tester.pumpAndSettle();
      expect(find.byType(PhoneRelayPage), findsOneWidget);
    });
  });
}
