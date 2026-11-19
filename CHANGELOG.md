# Changelog

## 0.2.1

- Ferb Link 0.2 closed as soon as it opened on a phone where it was already connected to your computer (after updating from 0.1, or reopening it after pairing). It opens again. Ferb's requests through Ferb Link worked throughout; only Ferb Link's own screen was affected.

## 0.2

This release is about code quality: a file-by-file review of Ferb Link and its server, following the principles of [pstack](https://github.com/cursor/plugins/tree/main/pstack).

- **Hindi voice.** Times and amounts are worked out when the model writes them in Devanagari ("शाम सवा सात बजे" sets 7:15 pm). A recording with a broken header gets "can't read the recording" instead of no answer.
- **Typed times.** "Remind me in 2 days at 7", "in 2 to 3 hours", "6:10 baje", "shaam ko 10 sec ka timer", "7 a.m." and "saade 13 baje" are read right.
- **Timeouts.** Ferb's timeout covers the whole call, so a slow computer can't hold Ferb longer than it asked. A timeout is no longer reported as "can't reach your computer".
- **Clearer errors.** A stopped server says so: "Start it: mise run server:start". With Tailscale off, Ferb Link sends nothing and asks whether Tailscale is on.
- **Keys.** Ferb Link's keys are no longer copied to a new phone in a device-to-device transfer. Plain HTTP goes out only inside your tailnet.
- **Jev.** Its spending is saved at once. A call that could pass the limit is refused ("Jev near its spending limit") and Jev stays on. A request with no tools isn't sent, or billed.
- **The screen.** The status no longer shows an old result after you change the address, Unload is hidden while Ferb Link is off, and an address typed in capitals is accepted.
- **Rewrite.** Ferb Link rewrites only with the prompt Ferb sends (every Ferb version sends one); its older built-in prompt is gone.
- **On the computer.** The server and Ferb's benchmark no longer run models side by side, a server start refused during a benchmark retries every 30 s, and `mise run server:chat` no longer loads the model just to start.

### If you moved to a new phone while using Ferb Link 0.1

Ferb Link 0.1 didn't stop Android from copying its settings during a phone-to-phone transfer. Those settings include your computer's API key, and your TypeSafe key if you entered one. Cloud backup was already off, so the keys went only to the phone you moved to. 0.2 stops this copying.

If you didn't move phones with 0.1 installed, there's nothing to do. If you did, both phones have the keys. To make a new computer key that only the phone you pair next will have, run this on your computer:

```sh
mise run server:key rotate
mise run server:pair
```

Then scan the code with the phone you use. For Jev, make a new key in your TypeSafe account and enter it in Ferb Link.

## 0.1

The first release.
