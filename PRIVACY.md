# Privacy policy

Pictureshow is a Jellyfin client for Android TV, published by Legitimate LLC. This policy covers the Pictureshow app.
It was last changed on 22 October 2026.

## What the app sends, and where

Pictureshow talks only to Jellyfin servers you sign in to. It has no ads, no analytics, no crash reporting and no
account with us, and it sends nothing to Legitimate LLC or to anyone else.

When you use it, the app sends your Jellyfin server:

- your user name and password when you sign in (the server answers with an access token, and the password is not
  kept);
- requests for your libraries, artwork and video streams;
- what you play and how far you got, so the server can keep your place and show it on your other devices;
- a description of what your TV can play, so the server only transcodes when it has to;
- the app's name and version, your TV's name (as set in its settings, or its model if it has none) and a random ID
  for this install, which the server shows in its list of devices.

To find servers on your home network, the app sends a short broadcast that only reaches devices on that network.

## What stays on your TV

The app keeps the address of each server you add, your user names, the access tokens, your recent searches, its
playback settings (such as streaming quality and subtitle style) and the random install ID in its private storage.
None of it is included in Android backups or device transfers. Signing out deletes that account's server address,
user name, token and searches, and asks the server to cancel the token. Uninstalling the app deletes everything.

If your TV's home screen has a Play Next or Watch Next row, the app adds what you're in the middle of to it: the title,
how far you got and a link to its artwork on your server. The access token is never part of that link. The row follows
your server's Continue Watching and Next Up lists, and signing out or switching users clears it. The home screen
belongs to your TV's maker, and what it does with the row is covered by their privacy policy.

## Your server

Your Jellyfin server keeps its own records, such as your watch history and its activity log. Whoever runs the server
controls them, and this policy doesn't cover them.

## Children

The app is not aimed at children and doesn't knowingly collect anything from them. It sends nothing to us, so we hold
nothing to delete.

## Changes and contact

If the app ever starts sending anything new, this policy will change first, and the change will be listed in the
release notes. Questions: info@legitimateapps.com.
