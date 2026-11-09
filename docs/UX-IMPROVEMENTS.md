# UX research and implementation

The review focused on the native desktop client/server and live media workflows. Existing Android work was preserved.

## Design references

- [Carbon color and Gray 100 layering](https://www.carbondesignsystem.com/building-blocks/foundations/color/overview): use #161616 as the canvas and progressively lighter surfaces; apply semantic color roles consistently.
- [Carbon text inputs](https://www.carbondesignsystem.com/building-blocks/core/components/text-input/guidelines): persistent labels, helper text and visible keyboard focus.
- [Carbon notifications](https://www.carbondesignsystem.com/building-blocks/core/components/notification/guidelines): keep task feedback readable until resolved or dismissed; distinguish errors from routine activity.
- [Carbon loading](https://www.carbondesignsystem.com/building-blocks/core/components/loading/guidelines): provide progress for user-initiated waiting without repeatedly interrupting the interface.

## Findings addressed

| Friction observed in source and native inspection | Change |
| --- | --- |
| A blank composer could report a successful send | Disable empty/whitespace-only sends, including keyboard submission |
| Text and attachments followed navigation into another destination | Keep drafts per community and channel in memory; clear them on lock |
| Completing a send could clear another channel's draft | Clear only the destination of that send |
| Sending required a pointer action | Add Ctrl+Enter (Command+Enter on macOS); retain Enter for newlines |
| Offline actions looked usable | Show offline guidance, preserve local reading/drafting, gate send/voice/connection actions |
| Routine background polling erased feedback and flashed a spinner | Preserve task messages during refresh; add a persistent, dismissible feedback area |
| Large channel lists required scanning | Add case-insensitive channel filtering and a no-results state |
| Removing an unwanted attachment required clearing all images | Add individual removal controls |
| The app opened to an extra channel-selection step | Open the first text channel after unlocking, joining or switching communities |
| Re-selecting the active community or voice channel interrupted a call | Ignore redundant navigation and compare voice channel IDs |
| The initial window could extend below a smaller display | Reduce default height to 720 and retain a tested 760×600 minimum |
| Secondary text and hover states varied from the requested theme | Centralize Gray 100 layer/secondary/focus tokens; reserve focus outlines for keyboard/active controls |
| A browser companion fragmented media controls and required JavaScript | Remove browser pages, bridge, browser tests and direct browser launcher dependency; implement native Rust capture, AVC and previews |
| Capture/receive states were hard to inspect | Add source selectors, live local previews, per-stream receive toggles, sharing status and stop controls in the app |

## Native media behavior

The video backend uses XCap and Nokhwa for device capture and source-built OpenH264 for software AVC. It preserves the encrypted relay and Android AVC packet framing. Screen sharing explicitly identifies the entire selected display. Opening the panel never starts capture. Closing it keeps sharing active, with a call-bar indicator; stopping, leaving, locking or worker/relay failure releases capture devices. Stop all sharing leaves incoming subscriptions alone.

## Verification boundaries

Regression tests exercise production egui controls, minimum-size offline composer layout, channel draft isolation, keyboard sending, feedback dismissal and native video encode/decode and relay lifecycle. Synthetic video tests verify concurrent sources, receive opt-in, bitrate updates and cleanup. Physical camera/microphone quality and macOS/Linux native execution require device/platform checks. Native video currently uses software AVC; hardware acceleration and VP8 are not provided by this backend.
