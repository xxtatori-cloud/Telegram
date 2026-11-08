import { createClient } from "https://esm.sh/@supabase/supabase-js@2";
import { cert, getApps, initializeApp } from "npm:firebase-admin@12/app";
import { getMessaging } from "npm:firebase-admin@12/messaging";

const supabaseUrl = Deno.env.get("SUPABASE_URL")!;
const serviceRoleKey = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const webhookSecret = Deno.env.get("MONOGRAM_WEBHOOK_SECRET")!;
const serviceAccount = JSON.parse(Deno.env.get("MONOGRAM_FIREBASE_SERVICE_ACCOUNT") || "{}");

if (getApps().length === 0) {
  initializeApp({ credential: cert(serviceAccount) });
}
const supabase = createClient(supabaseUrl, serviceRoleKey);

Deno.serve(async (request) => {
  if (request.method !== "POST") return new Response("Method not allowed", { status: 405 });
  if (request.headers.get("x-monogram-webhook-secret") !== webhookSecret) {
    return new Response("Unauthorized", { status: 401 });
  }

  try {
    const payload = await request.json();
    const message = payload.record;
    if (!message?.chat_id || !message?.sender_id) {
      return new Response("Missing message record", { status: 400 });
    }

    const { data: members, error: membersError } = await supabase
      .from("chat_members")
      .select("user_id")
      .eq("chat_id", message.chat_id)
      .neq("user_id", message.sender_id);
    if (membersError) throw membersError;

    const recipientIds = (members || []).map((member) => member.user_id);
    if (recipientIds.length === 0) return Response.json({ delivered: 0 });

    const { data: devices, error: devicesError } = await supabase
      .from("device_tokens")
      .select("fcm_token")
      .in("user_id", recipientIds);
    if (devicesError) throw devicesError;

    const tokens = [...new Set((devices || []).map((device) => device.fcm_token).filter(Boolean))];
    if (tokens.length === 0) return Response.json({ delivered: 0 });

    const result = await getMessaging().sendEachForMulticast({
      tokens,
      data: {
        title: "MONOGRAM",
        body: String(message.body || "New message").slice(0, 180),
        chat_id: String(message.chat_id),
        message_id: String(message.id || ""),
      },
      android: { priority: "high" },
    });

    return Response.json({
      delivered: result.successCount,
      failed: result.failureCount,
    });
  } catch (error) {
    console.error("MONOGRAM push error", error);
    return Response.json({ error: "Push delivery failed" }, { status: 500 });
  }
});
