import 'package:dsh_client/dsh_client.dart';
import 'package:flutter/foundation.dart';

import '../l10n/l10n.dart';
import 'engine_error_text.dart';

/// 「[action]：原因」, or just [action] when the reason means nothing to a
/// person. The raw error only goes to the log.
String chatErrorText(Object error, {required String action}) {
  debugPrint('$action: $error');
  final reason = switch (error) {
    DshRemoteException(:final code) => switch (code) {
      'session/agent-busy' => appL10n.stateChatAgentBusy,
      'session/writer-held' => appL10n.stateChatWriterHeld,
      'session/model-unavailable' || 'session/provider-models-unavailable' => appL10n.stateChatModelUnavailable,
      'session/attachment-invalid' => appL10n.stateChatAttachmentInvalid,
      'session/fork-unavailable' => appL10n.stateChatForkUnavailable,
      'FRAME_TOO_LARGE' => appL10n.stateChatImagesTooLarge,
      _ => engineErrorReason(error) ?? _rawReason(error),
    },
    // Uploads and exports go over HTTP: any other status means no engine.
    DshHttpException() => engineErrorReason(error) ?? appL10n.commonEngineUnreachable,
    _ => engineErrorReason(error),
  };
  return reason == null ? action : appL10n.stateActionReason(action, reason);
}

/// The engine's own words for a code the app has no sentence for: the code,
/// the message cut to about 120 characters, and the path it names, so a
/// failure on a phone can be read without a log. `debugPrint` above is the
/// only log the app keeps for this; there is no ring buffer to write to.
String _rawReason(DshRemoteException error) {
  final message = error.message.trim();
  final path = error.details['path'];
  final text = message.length > 120 ? '${message.substring(0, 119)}…' : message;
  return [error.code, if (text.isNotEmpty) text, if (path is String && path.isNotEmpty) path].join(' · ');
}
