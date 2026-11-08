# MONOGRAM backend setup

This repository now contains the first Supabase schema migration for MONOGRAM.

## What the migration provides

- Anonymous Supabase Auth users (no Telegram phone-number login).
- A profile created automatically for each auth user.
- A first-account owner marker, assigned atomically by the database.
- Unique usernames and MONOGRAM-style public numbers.
- Direct chat and membership tables, message storage, and Realtime publication.
- A free gift catalogue with a hard one-million serial limit per design.
- A database function that claims a gift without payment and allocates a unique serial.
- Free Premium entitlement defaults.
- Private avatar/media storage with per-user object policies.
- Row Level Security on profile, chat, message, and gift data.

## Required project setup

1. Create a dedicated Supabase project for MONOGRAM.
2. In Authentication settings, enable Anonymous Sign-Ins.
3. Apply `migrations/0001_monogram_core.sql` in the Supabase SQL editor.
4. Keep the project's publishable/anon key in the Android app configuration only. Never put a Supabase service-role key in the APK or repository.
5. Configure the app with the project's URL and publishable/anon key using local build configuration. Do not reuse the Firebase project configuration currently bundled for Telegram push services; that project is not MONOGRAM's backend.
6. Enable CAPTCHA / abuse controls before public launch.

## Important account limitation

Supabase anonymous accounts persist while the app retains its auth session, but they cannot be recovered after sign-out, app-data deletion, or on another device unless an identity is linked. A later account-recovery flow must link email, phone, or OAuth without changing the MONOGRAM username or profile.

## Current implementation boundary

This migration is the backend foundation, not a claim that the Android client is already fully connected. The current Telegram-derived message UI still depends on Telegram's MTProto controllers and must be routed to MONOGRAM's chat/message repository before it can exchange real messages through Supabase. Do not treat local `TLRPC.User` profile records as server authentication or a working chat session.

The Telegram-derived visual gift assets/catalogue still needs a dedicated MONOGRAM catalogue mapping. The database deliberately stores asset keys rather than claiming that Telegram's live gift catalogue is already available from MONOGRAM servers.
