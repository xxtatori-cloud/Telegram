# MONOGRAM backend

MONOGRAM uses its own Supabase project for identity, profiles, chats, messages,
gift ownership, media metadata and realtime delivery. Firebase is optional and
is used only as MONOGRAM's own Android push transport.

## Android configuration

The Android client reads these build-time values:

- `MONOGRAM_SUPABASE_URL`
- `MONOGRAM_SUPABASE_ANON_KEY`

Set them in `local.properties` for a local build:

```properties
MONOGRAM_SUPABASE_URL=https://YOUR_PROJECT.supabase.co
MONOGRAM_SUPABASE_ANON_KEY=YOUR_PUBLIC_ANON_OR_PUBLISHABLE_KEY
```

For GitHub Actions, add repository Actions secrets with the same names.
The anon/publishable key is intended for clients and is protected by RLS.
Never put the service-role key in the APK or in source control.

The Android backend client supports:
- Supabase anonymous account creation and persisted session restore.
- Server-created profile and first-account owner assignment.
- Username search.
- Direct chat creation.
- Server-side text message insert and message history retrieval.
- Supabase Realtime subscriptions for message inserts.
- Gift catalogue retrieval and atomic free gift claiming.
- Per-user device-token registration.

The current Telegram-derived screens still require the final bridge from their
MTProto-oriented dialog/message controllers to these repository methods. The
backend transport methods are real Supabase calls; local TLRPC objects alone
must not be mistaken for cloud chats.

## Database setup

1. Create a dedicated Supabase project for MONOGRAM.
2. Enable Anonymous Sign-Ins in Authentication settings.
3. Apply `migrations/0001_monogram_core.sql` in the SQL editor.
4. Enable Realtime for the `public.messages` table (the migration adds it to
   the publication).
5. Deploy the Edge Function in `functions/send-monogram-push/index.ts`.
6. Configure function secrets:
   - `SUPABASE_URL`
   - `SUPABASE_SERVICE_ROLE_KEY`
   - `MONOGRAM_WEBHOOK_SECRET`
   - `MONOGRAM_FIREBASE_SERVICE_ACCOUNT` (the JSON service-account document)
7. Create a Database Webhook on `public.messages`, event INSERT, pointing to
   `send-monogram-push`. Send header
   `x-monogram-webhook-secret: <MONOGRAM_WEBHOOK_SECRET>`.
8. Seed `gift_catalog` with each design's unique slug, title and storage/resource
   asset key. Every row is constrained to zero price and one million serials.

## Dedicated Firebase push project

Do not reuse Telegram's Firebase project or its google-services file.
Create a Firebase project owned by MONOGRAM, register the Android application
using the standalone application ID, download its `google-services.json`,
base64-encode it, and store it in the GitHub Actions secret
`MONOGRAM_GOOGLE_SERVICES_JSON_BASE64`. For local builds, place that file at
`TMessagesProj_AppStandalone/google-services.json`.

The Google Services plugin is applied only when that dedicated file exists.
The standalone manifest uses `MonogramFirebaseMessagingService`, which
registers FCM tokens against the authenticated Supabase user. The Edge Function
sends message notifications to the other members' devices.

## Account recovery

Anonymous Supabase accounts remain available while their auth session is
preserved. They cannot be recovered after app-data deletion or on a new device
until the user links a durable identity (email/OAuth/phone). A secure account
linking/recovery screen is still required before public release.

## Gift catalogue and media

The database catalogue stores an `asset_key`, not the actual artwork. Upload
originally licensed or user-owned gift artwork to the private `monogram-media`
bucket and use its object key. Do not scrape Telegram's private gift CDN or
assume that a gift asset downloaded in a Telegram session is automatically
licensed for MONOGRAM redistribution.

## Security

- Row Level Security is enabled on all user data tables.
- The app uses only the public anon/publishable key.
- The service-role key is used only by server-side functions.
- The first-account owner flag is assigned by a database trigger and cannot be
  changed through the Android client.
- Gift claims allocate serial numbers transactionally and enforce a hard
  one-million-per-design supply limit.
