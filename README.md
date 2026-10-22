# LDAP Contacts

Makes a corporate LDAP address book searchable from the system Contacts and
Dialer apps on Android, with optional caller ID for numbers in the directory.
Each search is one LDAPS query; nothing is cached on the phone.

You set it up with your own directory account, the way you would add an LDAP
address book to a mail client: server, search base, bind DN, login, password.

## Configuration

The app ships knowing no directory. On first run it asks which one to search:

| Setting | Example |
| --- | --- |
| LDAP server | `ldap.example.org` |
| Search base DN | `dc=example,dc=org` |
| Bind DN pattern | `uid=%s,ou=people,dc=example,dc=org` |

In the bind DN pattern, `%s` stands for the login; the pattern is rejected
without it. Type the three values, or scan a QR code that carries them — see
below. Once they are set, this screen is done with: from then on the app only
asks for your login and password, which are checked with a single bind before
being stored.

To start over, clear the app's data and open it again.

### Configuration QR code

Whoever runs a directory can publish a QR code so nobody has to type DNs on a
phone. It holds plain `key = value` lines:

```
server="ldap.example.org"
base_dn="dc=example,dc=org"
bind_dn="uid=%s,ou=people,dc=example,dc=org"
```

Parsing is lenient, because a printed code cannot be corrected: `=` or `:`
separates, quotes are optional, blank lines and `#` comments are skipped, key
spelling ignores case and punctuation (`base_dn`, `baseDN`, `basedn`), only the
first separator counts so a DN keeps its own `=` signs, and unknown keys are
ignored instead of failing the code. A `ldaps://host/` server is accepted as
well as a bare host name.

Scanned values are shown in the fields for you to check, not applied silently.
A configuration QR code carries no login and no password: it is meant to be
printed or published.

The camera permission is only requested when the scan button is used, so it can
be granted for that one time, or never granted if you type the values instead.

What is still fixed in the code, and the natural next thing to open up
(contributions welcome):

| What | Where |
| --- | --- |
| Port 636, LDAPS only | `Config.PORT` |
| Attribute names read from an entry | `LdapClient.ATTRS`, `Person.fromEntry` |

So the app works against a directory whose schema matches those; for anything
else the attribute mapping has to be adjusted in code for now.

The server must present a certificate that the Android system trust store
accepts for the host name you configure: the certificate is verified and the
host name is checked. Plain LDAP and StartTLS are deliberately not offered.

## Credentials

The app binds to LDAP as you, so it needs your directory password, and keeps it
on the phone encrypted with an AES-GCM key that never leaves the Android
Keystore. It is never put in AccountManager (which keeps account passwords in
clear text), never logged, and excluded from backups. It is decrypted only to
open a connection.

Be aware of what this means before using it with a work account:

- Typing a corporate password into a third-party app is something many IT
  departments forbid, and some directories are reachable only through their
  single sign-on.
- An LDAP bind takes the password alone, so it bypasses two-factor
  authentication on the account.
- A password is an unscoped credential: if the stored one leaks, the whole
  account is exposed, not just address book access.

A refused password stops every further attempt until you type it again: Contacts
sends up to three queries per keystroke, so retrying would walk the account into
a lockout.

## Build and install (Ubuntu, from scratch)

Requirements: a JDK 17 or 21 (Gradle 8.11 does not run on JDK 25; a JRE is
not enough) and the Android SDK with API 35.

```bash
# 1. System packages
sudo apt install openjdk-21-jdk-headless adb

# 2. Android SDK: "Command line tools only" from developer.android.com/studio,
#    unpacked into ~/Android/Sdk/cmdline-tools/latest/
export ANDROID_HOME=$HOME/Android/Sdk
~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME \
    "platform-tools" "platforms;android-35" "build-tools;35.0.0"
yes | ~/Android/Sdk/cmdline-tools/latest/bin/sdkmanager --sdk_root=$ANDROID_HOME --licenses

# 3. Project configuration (local.properties is not under git)
echo "sdk.dir=$ANDROID_HOME" > local.properties

# 4. Build
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./gradlew assembleDebug

# 5. Install on the phone (Developer options > USB debugging, then authorise the PC)
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Alternatively, open the folder in Android Studio.

- **debug**: signed with the committed `app/debug.keystore` so successive builds
  install over each other.
- **release**: unsigned (F-Droid signs it). It cannot be installed as it is.

CI (`.gitlab-ci.yml`) builds both on every push and keeps the APKs as job
artifacts.

## Use

1. Open "LDAP Contacts". On first run, set the directory (type it or scan the QR
   code), then type your login and password and tap "Sign in".
2. Try a search in the Test section.
3. In the Dialer or Contacts, search for a colleague (at least 3 characters):
   the results appear in a separate section named after the server.
4. Optional: "Enable caller ID" shows the name of incoming calls from numbers in
   the directory that are not in your contacts (a notification; the call is
   never blocked). It takes the call screening role, so it replaces the
   manufacturer's spam filter, if any.

## Behaviour

- Search by name tokens (all must match) or by partial number; at most 25
  results, minimum 3 characters.
- A `*` typed in a search stays a literal asterisk: filter values are escaped,
  so a query cannot be turned into a wildcard that scrapes the directory.
- Caller ID looks up every incoming number that is not in local contacts, in
  E.164 form; national numbers get the country code of the mobile network.
- Results are held in memory for a few seconds, because Contacts sends the same
  query up to three times per keystroke. Nothing is written to disk.
- If the directory refuses the password, searching stops until it is entered
  again: a persistent notification plus a placeholder result in Contacts. It
  never fails silently.
- "Sign out" (or removing the account in system settings) forgets the password.
- The directory provider can only be read by apps holding READ_CONTACTS.
- The app never reads or writes your local contacts: READ_CONTACTS and
  WRITE_CONTACTS are only there to register the directory and to ask
  ContactsProvider to rescan it.

## Debug

```bash
adb logcat -s LdapContacts
adb shell content query --uri content://com.android.contacts/directories
```

## License

Apache License 2.0, see [LICENSE](LICENSE).

Bundled:

- [UnboundID LDAP SDK for Java](https://github.com/pingidentity/ldapsdk), used
  under the Apache License 2.0 (the SDK is also offered under GPLv2 and
  LGPLv2.1).
- [ZXing Android Embedded](https://github.com/journeyapps/zxing-android-embedded)
  and [ZXing core](https://github.com/zxing/zxing), Apache License 2.0.
- `androidx.core`, Apache License 2.0. Needed only because the scanner's
  CaptureManager calls ContextCompat and ActivityCompat without declaring any
  AndroidX dependency of its own.
