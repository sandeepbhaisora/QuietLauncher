#!/usr/bin/env python3
"""Build a second APK by removing notification listener from the compiled manifest."""
from pathlib import Path
import re

manifest = Path("app/src/main/AndroidManifest.xml")
source = manifest.read_text(encoding="utf-8")
pattern = r'\s*<service\b[^>]*\bandroid:name="\.LondeNotificationListener"[\s\S]*?</service>'
updated, count = re.subn(pattern, "", source)
if count != 1:
    raise SystemExit(f"Expected exactly one notification service, found {count}")
for token in (".LondeNotificationListener", "android.permission.BIND_NOTIFICATION_LISTENER_SERVICE",
              "android.service.notification.NotificationListenerService"):
    if token in updated:
        raise SystemExit("Unexpected notification registration: " + token)
manifest.write_text(updated, encoding="utf-8")
print("Prepared standard APK manifest without privileged notification listener")
