# Privacy Policy — Focus Blocker

**Last updated:** 11 August 2026

## Summary

Focus Blocker does not collect, transmit or sell any personal data. There are no accounts,
no analytics, and no tracking. Everything you configure stays on your device.

## What the app stores, and where

All of your settings — blocked apps, blocked websites, blocked keywords, whitelists,
motivation videos, protection levels and scheduled changes — are stored **only on your
device**, in the app's private storage. They are never uploaded. Uninstalling the app
deletes them.

## Screen content and the Accessibility Service

To block distracting apps, websites and settings pages, Focus Blocker uses Android's
Accessibility Service, which lets it read what is currently on screen.

This is the most sensitive permission the app uses, so to be explicit:

- Screen content is examined **entirely on your device**, in memory, as it is read.
- It is **never** written to a file, never stored, and **never transmitted anywhere**.
- The component that reads your screen contains no networking code of any kind.
- Adult-content detection runs a small classifier **on the device**. It is a fixed set of
  numbers built into the app — there is no cloud service, no lookup, and nothing is sent
  away for scoring.
- Text typed into password and input fields is deliberately excluded from analysis.

## Network connections

The app makes only these connections, and none of them carry personal data:

| Connection | Purpose | What is sent |
|---|---|---|
| `raw.githubusercontent.com` | Downloads a public list of adult domains used for blocking | Nothing about you — a plain download |
| `youtube.com`, `instagram.com`, `tiktok.com` | Loads the motivation videos **you** chose to add | The video links you saved, to display them |
| Google Play | Processes optional donations | Handled entirely by Google Play; the app never sees payment details |

The app does not contact any server operated by us.

## Donations

Donations are optional and processed by Google Play's billing system. We never receive or
store your payment information. See Google's own privacy policy for how Play handles it.

## Data sharing

We do not share, sell or transfer any data, because we do not collect any.

## Children

Focus Blocker is not directed at children under 13.

## Permissions and why they are needed

- **Accessibility Service** — detect and block distracting apps, sites and settings pages
- **Display over other apps** — show the blocking screen
- **Device Admin** — optional uninstall protection you can enable and disable
- **Query installed apps** — let you choose which installed apps to block
- **Notifications / foreground service** — keep blocking running in the background
- **Boot completed** — resume blocking after a restart
- **Exact alarms** — apply changes that are on the 24-hour delay

## Changes

If this policy changes, the date at the top will be updated.

## Contact

dodomaimon@gmail.com
