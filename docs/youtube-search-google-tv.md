# YouTube channel verification and Google TV input

OpenPanel does not require a YouTube API key. In **Admin Panel → YouTube**, an
administrator can enter a channel name, unique `@handle`, channel ID, or
channel URL. The native Android bridge:

1. normalizes the entry into an exact YouTube channel URL;
2. downloads that public channel page;
3. requires a canonical `UC…` channel ID in the response;
4. extracts the channel title and artwork; and
5. returns the canonical `youtube.com/channel/UC…` URL to the Admin Panel.

The **Add** button appears only after verification succeeds. Invalid and missing
handles are rejected instead of creating a broken launcher tile.

YouTube channel display names are not unique, while handles are unique. A plain
name such as `Google Developers` is therefore tried as the exact handle
`@GoogleDevelopers`. If a channel's display name and handle differ, enter the
unique `@handle`. This intentionally avoids scraping YouTube's general search
results.

Video and playlist URLs can still be pasted directly. No credential is created,
requested, stored, or shipped in the APK.

## Browsing an approved channel

Opening a verified channel tile does not start an arbitrary or automatically
selected video. OpenPanel requests YouTube's public Atom feed for the approved
canonical channel ID, validates the returned channel and video IDs in the
native Android bridge, and shows the channel's recent uploads as a D-pad- and
touch-friendly grid. The user chooses a video, which then opens in the same
restricted player used for individually approved video links.

This is intentionally not general YouTube search: the browser exposes only
recent uploads from the channel that an administrator approved. Older
handle-only entries that predate canonical-ID verification must be removed and
added again before the recent-video browser can load them.

## Google TV input behavior

- OpenPanel detects television UI mode, Leanback support, touch availability,
  attached D-pad/game controllers, alphabetic keyboards, and the active remote
  device name when Android exposes it.
- PIN creation and entry use OpenPanel's on-screen numeric keypad, so Gboard no
  longer covers or resizes the PIN card. D-pad arrows move predictably between
  keys; OK selects; hardware number keys also work.
- The YouTube channel field is declared as a search input, allowing Google TV
  Gboard to select its TV search layout and built-in speech-to-text.
- The microphone button invokes Android's system speech recognizer and fills
  the channel field with the result. OpenPanel does not request direct microphone
  permission.
- Some ArborXR lock-task policies block the recognizer's separate system
  Activity even when Android reports it as installed. OpenPanel falls back to
  focusing the channel field and tells the administrator to press the Google TV
  remote's microphone button, which uses the supported TV keyboard/Gboard
  dictation path.
- Recovery-answer and Wi-Fi fields no longer force Gboard to open immediately
  on a remote-driven device. The keyboard opens only when the user selects the
  field.
