# 27 — UI trim: less text, outlined cards, smaller History rows

## Status
- Screens carry labels and values only. Intro paragraphs and hints under titles are gone; gate
  rows state the cause and show an arrow instead of "tap to …". Error lines keep their cause.
- `ui/components/HushCard.kt` is the one card (page colour, visible outline) on Focus,
  "Apps & throttles" and Settings. Error cards keep the error container colour.
- History rows are one text step smaller. The unfolded row offers only "Throttle this chat":
  the "Only this chat / Only this app" jumps and the "Tap opens …" hint from #20 are removed,
  with `HistoryViewModel.showApp/showChat/opensOriginal` and `NotificationOpener.hasOriginalTarget`.
  The filter chips are the way to narrow History.
- The default interval is picked with the same `ThrottleChip` as every other throttle; the M3
  segmented row from #6 is gone (its labels sat off-centre without the check icon).

## Decision
The owner asked for far less on-screen explanation after the first on-device look. Where a control
needed a sentence to be understood, the sentence went and the control has to speak for itself.
