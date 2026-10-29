# Privacy

Ferb Link has no account, no ads, no analytics and no crash reporting. It talks to two places only, both of your choosing: your own computer, and TypeSafe if you turn Jev on.

## What goes to your computer

When Ferb Link is on and connected, Ferb sends it your requests, and Ferb Link passes them to the address you paired:

- **What you type or say to Ferb:** the text of each request, and the recording when you speak (Ferb Link converts it to a standard sound format first). Also Ferb's instructions for the model: its prompt, and its tools' names and descriptions.
- **How:** over [Tailscale](https://tailscale.com), which encrypts everything between your own devices. Ferb Link sends plain HTTP only to Tailscale names (`….ts.net`), and HTTPS anywhere else.
- **Who can read it:** only devices on your tailnet can reach the server, and they also need its key.
- **What the computer keeps:** the server's log has timings and token counts, not the words or recordings. The model holds the last request in its memory until it unloads (after 5 minutes idle, or when you tap Unload).

When Ferb Link's main switch is off, or your computer can't be reached, nothing goes to your computer: Ferb answers on the phone. Jev, below, has its own switch.

## What goes to TypeSafe (Jev), only if you turn it on

Jev is off by default. It runs only when you have turned on both **Let Ferb use Jev** in Ferb Link and **Use Jev to select the tool** in Ferb, and entered your own TypeSafe key. Then:

- **What's sent:** the text of every request you make to Ferb, and the names and descriptions of Ferb's tools. Not recordings, contacts, notes or other app data.
- **Where:** TypeSafe's servers in the US (`api.typesafe.ai`), over HTTPS. TypeSafe says it doesn't train on it; see [TypeSafe's privacy policy](https://typesafe.ai/legal/privacy-policy).
- **Cost:** each request costs a little money on your TypeSafe account. Ferb Link counts it and turns Jev off at your spending limit ($3 unless you change it).

## What's stored on the phone

In Ferb Link's private storage, which other apps can't read, and which isn't backed up or copied to a new phone:

- your computer's address and its key;
- your TypeSafe key, if you entered one, and Jev's spending so far and its limit;
- whether Ferb Link is on, the model's name and state, and which hints you've dismissed.

The keys are stored as they are, not encrypted with a separate key. Uninstalling Ferb Link, or clearing its storage, deletes all of it.

**Recent requests:** the last 30 requests, with what was heard, are kept in memory so you can see what left the phone. They are gone when the app closes. Android's system log (`adb logcat`) gets only which method was called, by which app, and how long it took.

## Who can use Ferb Link

Only apps signed with the same key as Ferb Link, which is Ferb. Other apps can't send requests through it.

## Questions

Open an issue at [github.com/kshatriya-abhay/ferb-link](https://github.com/kshatriya-abhay/ferb-link/issues).
