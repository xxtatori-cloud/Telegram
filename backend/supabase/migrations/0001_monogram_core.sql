-- MONOGRAM backend foundation (Supabase / PostgreSQL)
-- Apply in the Supabase SQL editor after enabling Anonymous Sign-ins.
-- Every gift is free. Each design has a one-million serial supply.

create extension if not exists pgcrypto;

create table if not exists public.monogram_instance (
    singleton boolean primary key default true check (singleton),
    owner_user_id uuid references auth.users(id),
    created_at timestamptz not null default now()
);

insert into public.monogram_instance(singleton)
values (true)
on conflict (singleton) do nothing;

create table if not exists public.profiles (
    user_id uuid primary key references auth.users(id) on delete cascade,
    username text not null unique,
    first_name text not null default '',
    last_name text not null default '',
    avatar_url text,
    monogram_number text not null unique,
    is_owner boolean not null default false,
    premium_enabled boolean not null default true,
    username_is_collectible boolean not null default false,
    username_asset_id text,
    customization jsonb not null default '{}'::jsonb,
    created_at timestamptz not null default now(),
    updated_at timestamptz not null default now(),
    constraint profiles_username_format check (username ~ '^[A-Za-z0-9_]{5,32}$')
);

create or replace function public.monogram_handle_new_user()
returns trigger
language plpgsql
security definer
set search_path = public, auth
as $$
declare
    v_owner uuid;
    v_username text;
    v_number text;
    v_first text;
    v_last text;
begin
    insert into public.monogram_instance(singleton)
    values (true)
    on conflict (singleton) do nothing;

    select owner_user_id into v_owner
    from public.monogram_instance
    where singleton = true
    for update;

    if v_owner is null then
        update public.monogram_instance
        set owner_user_id = new.id
        where singleton = true;
        v_owner := new.id;
    end if;

    v_username := coalesce(
        nullif(new.raw_user_meta_data ->> 'username', ''),
        'user_' || lower(substr(replace(new.id::text, '-', ''), 1, 12))
    );
    v_first := coalesce(new.raw_user_meta_data ->> 'first_name', '');
    v_last := coalesce(new.raw_user_meta_data ->> 'last_name', '');

    loop
        v_number := 'MN-' || lpad((floor(random() * 100000000)::bigint)::text, 8, '0');
        exit when not exists (
            select 1 from public.profiles where monogram_number = v_number
        );
    end loop;

    insert into public.profiles(
        user_id, username, first_name, last_name, monogram_number,
        is_owner, premium_enabled
    )
    values (
        new.id, v_username, v_first, v_last, v_number,
        (new.id = v_owner), true
    )
    on conflict (user_id) do nothing;

    return new;
end;
$$;

drop trigger if exists on_monogram_auth_user_created on auth.users;
create trigger on_monogram_auth_user_created
after insert on auth.users
for each row execute procedure public.monogram_handle_new_user();

create table if not exists public.chats (
    id uuid primary key default gen_random_uuid(),
    created_by uuid not null references auth.users(id),
    title text,
    is_group boolean not null default false,
    created_at timestamptz not null default now()
);

create table if not exists public.chat_members (
    chat_id uuid not null references public.chats(id) on delete cascade,
    user_id uuid not null references auth.users(id) on delete cascade,
    joined_at timestamptz not null default now(),
    primary key (chat_id, user_id)
);

create table if not exists public.messages (
    id uuid primary key default gen_random_uuid(),
    chat_id uuid not null references public.chats(id) on delete cascade,
    sender_id uuid not null references auth.users(id),
    body text not null default '',
    media_url text,
    created_at timestamptz not null default now(),
    edited_at timestamptz,
    deleted_at timestamptz
);

create or replace function public.monogram_is_chat_member(p_chat_id uuid)
returns boolean
language sql
stable
security definer
set search_path = public, auth
as $$
    select exists (
        select 1 from public.chat_members
        where chat_id = p_chat_id and user_id = auth.uid()
    );
$$;

create or replace function public.create_direct_chat(p_other_user_id uuid)
returns uuid
language plpgsql
security definer
set search_path = public, auth
as $$
declare
    v_chat_id uuid;
begin
    if auth.uid() is null or p_other_user_id is null or p_other_user_id = auth.uid() then
        raise exception 'Invalid chat participants';
    end if;

    select c.id into v_chat_id
    from public.chats c
    where c.is_group = false
      and exists (select 1 from public.chat_members a where a.chat_id = c.id and a.user_id = auth.uid())
      and exists (select 1 from public.chat_members b where b.chat_id = c.id and b.user_id = p_other_user_id)
      and (select count(*) from public.chat_members m where m.chat_id = c.id) = 2
    limit 1;

    if v_chat_id is not null then
        return v_chat_id;
    end if;

    insert into public.chats(created_by, is_group)
    values (auth.uid(), false)
    returning id into v_chat_id;

    insert into public.chat_members(chat_id, user_id)
    values (v_chat_id, auth.uid()), (v_chat_id, p_other_user_id);

    return v_chat_id;
end;
$$;

create index if not exists messages_chat_created_idx
on public.messages(chat_id, created_at desc);

create table if not exists public.gift_catalog (
    slug text primary key,
    title text not null,
    asset_key text not null,
    supply_limit integer not null default 1000000 check (supply_limit = 1000000),
    next_serial integer not null default 1,
    price_stars integer not null default 0 check (price_stars = 0),
    enabled boolean not null default true,
    created_at timestamptz not null default now()
);

create table if not exists public.user_gifts (
    id uuid primary key default gen_random_uuid(),
    gift_slug text not null references public.gift_catalog(slug),
    serial_number integer not null,
    owner_id uuid not null references auth.users(id) on delete cascade,
    received_at timestamptz not null default now(),
    is_equipped boolean not null default false,
    unique(gift_slug, serial_number)
);

create index if not exists user_gifts_owner_idx
on public.user_gifts(owner_id, received_at desc);

create table if not exists public.username_assets (
    username text primary key,
    owner_id uuid not null unique references auth.users(id) on delete cascade,
    claimed_at timestamptz not null default now(),
    constraint username_assets_format check (username ~ '^[A-Za-z0-9_]{5,32}

create or replace function public.claim_monogram_gift(p_slug text)
returns public.user_gifts
language plpgsql
security definer
set search_path = public, auth
as $$
declare
    v_gift public.gift_catalog%rowtype;
    v_result public.user_gifts%rowtype;
    v_serial integer;
begin
    if auth.uid() is null then
        raise exception 'Authentication required';
    end if;

    select * into v_gift
    from public.gift_catalog
    where slug = p_slug and enabled = true
    for update;

    if not found then
        raise exception 'Gift not found';
    end if;

    if v_gift.price_stars <> 0 then
        raise exception 'MONOGRAM gifts must be free';
    end if;

    v_serial := v_gift.next_serial;
    if v_serial > v_gift.supply_limit then
        raise exception 'This gift design has reached its supply limit';
    end if;

    update public.gift_catalog
    set next_serial = next_serial + 1
    where slug = p_slug;

    insert into public.user_gifts(gift_slug, serial_number, owner_id)
    values (p_slug, v_serial, auth.uid())
    returning * into v_result;

    return v_result;
end;
$$;

create table if not exists public.device_tokens (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    fcm_token text not null unique,
    platform text not null default 'android',
    updated_at timestamptz not null default now(),
    created_at timestamptz not null default now()
);

create index if not exists device_tokens_user_idx
on public.device_tokens(user_id);

alter table public.username_assets enable row level security;
alter table public.profiles enable row level security;
alter table public.device_tokens enable row level security;
alter table public.chats enable row level security;
alter table public.chat_members enable row level security;
alter table public.messages enable row level security;
alter table public.gift_catalog enable row level security;
alter table public.user_gifts enable row level security;
alter table public.monogram_instance enable row level security;

create policy "Users manage their own push tokens"
on public.device_tokens for all to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

create policy "Collectible usernames are visible to signed-in users"
on public.username_assets for select to authenticated
using (true);

create policy "Users can view their own collectible username asset"
on public.username_assets for select to authenticated
using (owner_id = auth.uid());

create policy "Profiles are visible to signed-in users"
on public.profiles for select to authenticated
using (true);

create policy "Users update their own profile"
on public.profiles for update to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

create policy "Members can view their chats"
on public.chats for select to authenticated
using (public.monogram_is_chat_member(chats.id));

create policy "Members can view chat membership"
on public.chat_members for select to authenticated
using (public.monogram_is_chat_member(chat_members.chat_id));

create policy "Chat members can read messages"
on public.messages for select to authenticated
using (public.monogram_is_chat_member(messages.chat_id));

create policy "Chat members can send messages as themselves"
on public.messages for insert to authenticated
with check (
    sender_id = auth.uid()
    and public.monogram_is_chat_member(messages.chat_id)
);

create policy "Gift catalog is readable"
on public.gift_catalog for select to authenticated
using (enabled = true);

create policy "Users can view gifts they own"
on public.user_gifts for select to authenticated
using (owner_id = auth.uid());

create policy "MONOGRAM settings are not directly readable"
on public.monogram_instance for select to authenticated
using (false);

grant select on public.profiles, public.gift_catalog, public.user_gifts, public.username_assets to authenticated;
grant select, insert, update, delete on public.device_tokens to authenticated;
grant update (first_name, last_name, avatar_url, customization, updated_at)
on public.profiles to authenticated;
grant select on public.chats, public.chat_members, public.messages to authenticated;
grant insert (chat_id, sender_id, body, media_url) on public.messages to authenticated;
grant execute on function public.claim_monogram_gift(text) to authenticated;
grant execute on function public.create_direct_chat(uuid) to authenticated;
grant execute on function public.create_group_chat(text, uuid[]) to authenticated;
grant execute on function public.claim_collectible_username(text) to authenticated;
grant execute on function public.equip_monogram_gift(uuid) to authenticated;

-- Private media storage. Object names must start with the authenticated user UUID.
insert into storage.buckets(id, name, public)
values ('monogram-media', 'monogram-media', false)
on conflict (id) do nothing;

create policy "MONOGRAM users upload their own media"
on storage.objects for insert to authenticated
with check (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

create policy "MONOGRAM users read their own media"
on storage.objects for select to authenticated
using (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

create policy "MONOGRAM users update their own media"
on storage.objects for update to authenticated
using (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
)
with check (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

create policy "MONOGRAM users delete their own media"
on storage.objects for delete to authenticated
using (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

-- Enable Supabase Realtime for message updates.
do $$
begin
    alter publication supabase_realtime add table public.messages;
exception when duplicate_object then
    null;
end;
$$;
)
);

create or replace function public.claim_collectible_username(p_username text)
returns public.profiles
language plpgsql
security definer
set search_path = public, auth
as $
declare
    v_username text;
    v_profile public.profiles%rowtype;
begin
    if auth.uid() is null then
        raise exception 'Authentication required';
    end if;
    v_username := trim(both '@' from coalesce(p_username, ''));
    if v_username !~ '^[A-Za-z0-9_]{5,32}

create or replace function public.claim_monogram_gift(p_slug text)
returns public.user_gifts
language plpgsql
security definer
set search_path = public, auth
as $$
declare
    v_gift public.gift_catalog%rowtype;
    v_result public.user_gifts%rowtype;
    v_serial integer;
begin
    if auth.uid() is null then
        raise exception 'Authentication required';
    end if;

    select * into v_gift
    from public.gift_catalog
    where slug = p_slug and enabled = true
    for update;

    if not found then
        raise exception 'Gift not found';
    end if;

    if v_gift.price_stars <> 0 then
        raise exception 'MONOGRAM gifts must be free';
    end if;

    v_serial := v_gift.next_serial;
    if v_serial > v_gift.supply_limit then
        raise exception 'This gift design has reached its supply limit';
    end if;

    update public.gift_catalog
    set next_serial = next_serial + 1
    where slug = p_slug;

    insert into public.user_gifts(gift_slug, serial_number, owner_id)
    values (p_slug, v_serial, auth.uid())
    returning * into v_result;

    return v_result;
end;
$$;

create table if not exists public.device_tokens (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    fcm_token text not null unique,
    platform text not null default 'android',
    updated_at timestamptz not null default now(),
    created_at timestamptz not null default now()
);

create index if not exists device_tokens_user_idx
on public.device_tokens(user_id);

alter table public.profiles enable row level security;
alter table public.device_tokens enable row level security;
alter table public.chats enable row level security;
alter table public.chat_members enable row level security;
alter table public.messages enable row level security;
alter table public.gift_catalog enable row level security;
alter table public.user_gifts enable row level security;
alter table public.monogram_instance enable row level security;

create policy "Users manage their own push tokens"
on public.device_tokens for all to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

create policy "Profiles are visible to signed-in users"
on public.profiles for select to authenticated
using (true);

create policy "Users update their own profile"
on public.profiles for update to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

create policy "Members can view their chats"
on public.chats for select to authenticated
using (public.monogram_is_chat_member(chats.id));

create policy "Members can view chat membership"
on public.chat_members for select to authenticated
using (public.monogram_is_chat_member(chat_members.chat_id));

create policy "Chat members can read messages"
on public.messages for select to authenticated
using (public.monogram_is_chat_member(messages.chat_id));

create policy "Chat members can send messages as themselves"
on public.messages for insert to authenticated
with check (
    sender_id = auth.uid()
    and public.monogram_is_chat_member(messages.chat_id)
);

create policy "Gift catalog is readable"
on public.gift_catalog for select to authenticated
using (enabled = true);

create policy "Users can view gifts they own"
on public.user_gifts for select to authenticated
using (owner_id = auth.uid());

create policy "MONOGRAM settings are not directly readable"
on public.monogram_instance for select to authenticated
using (false);

grant select on public.profiles, public.gift_catalog, public.user_gifts to authenticated;
grant select, insert, update, delete on public.device_tokens to authenticated;
grant update (username, first_name, last_name, avatar_url, customization, updated_at)
on public.profiles to authenticated;
grant select on public.chats, public.chat_members, public.messages to authenticated;
grant insert (chat_id, sender_id, body, media_url) on public.messages to authenticated;
grant execute on function public.claim_monogram_gift(text) to authenticated;
grant execute on function public.create_direct_chat(uuid) to authenticated;

-- Private media storage. Object names must start with the authenticated user UUID.
insert into storage.buckets(id, name, public)
values ('monogram-media', 'monogram-media', false)
on conflict (id) do nothing;

create policy "MONOGRAM users upload their own media"
on storage.objects for insert to authenticated
with check (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

create policy "MONOGRAM users read their own media"
on storage.objects for select to authenticated
using (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

create policy "MONOGRAM users update their own media"
on storage.objects for update to authenticated
using (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
)
with check (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

create policy "MONOGRAM users delete their own media"
on storage.objects for delete to authenticated
using (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

-- Enable Supabase Realtime for message updates.
do $$
begin
    alter publication supabase_realtime add table public.messages;
exception when duplicate_object then
    null;
end;
$$;
 then
        raise exception 'Username must contain 5 to 32 letters, numbers or underscores';
    end if;

    insert into public.username_assets(username, owner_id)
    values (v_username, auth.uid());

    update public.profiles
    set username = v_username,
        username_is_collectible = true,
        username_asset_id = v_username,
        updated_at = now()
    where user_id = auth.uid()
    returning * into v_profile;

    return v_profile;
end;
$;

create or replace function public.equip_monogram_gift(p_gift_id uuid)
returns public.user_gifts
language plpgsql
security definer
set search_path = public, auth
as $
declare
    v_gift public.user_gifts%rowtype;
begin
    if auth.uid() is null then
        raise exception 'Authentication required';
    end if;
    update public.user_gifts
    set is_equipped = false
    where owner_id = auth.uid();
    update public.user_gifts
    set is_equipped = true
    where id = p_gift_id and owner_id = auth.uid()
    returning * into v_gift;
    if not found then
        raise exception 'Gift does not belong to this account';
    end if;
    return v_gift;
end;
$;

create or replace function public.create_group_chat(p_title text, p_member_ids uuid[])
returns uuid
language plpgsql
security definer
set search_path = public, auth
as $
declare
    v_chat_id uuid;
    v_member uuid;
begin
    if auth.uid() is null then
        raise exception 'Authentication required';
    end if;
    if p_title is null or length(trim(p_title)) = 0 then
        raise exception 'Group title is required';
    end if;
    insert into public.chats(created_by, title, is_group)
    values (auth.uid(), left(trim(p_title), 128), true)
    returning id into v_chat_id;

    insert into public.chat_members(chat_id, user_id)
    values (v_chat_id, auth.uid());

    foreach v_member in array coalesce(p_member_ids, array[]::uuid[]) loop
        if v_member <> auth.uid() then
            insert into public.chat_members(chat_id, user_id)
            values (v_chat_id, v_member)
            on conflict do nothing;
        end if;
    end loop;
    return v_chat_id;
end;
$;



create or replace function public.claim_monogram_gift(p_slug text)
returns public.user_gifts
language plpgsql
security definer
set search_path = public, auth
as $$
declare
    v_gift public.gift_catalog%rowtype;
    v_result public.user_gifts%rowtype;
    v_serial integer;
begin
    if auth.uid() is null then
        raise exception 'Authentication required';
    end if;

    select * into v_gift
    from public.gift_catalog
    where slug = p_slug and enabled = true
    for update;

    if not found then
        raise exception 'Gift not found';
    end if;

    if v_gift.price_stars <> 0 then
        raise exception 'MONOGRAM gifts must be free';
    end if;

    v_serial := v_gift.next_serial;
    if v_serial > v_gift.supply_limit then
        raise exception 'This gift design has reached its supply limit';
    end if;

    update public.gift_catalog
    set next_serial = next_serial + 1
    where slug = p_slug;

    insert into public.user_gifts(gift_slug, serial_number, owner_id)
    values (p_slug, v_serial, auth.uid())
    returning * into v_result;

    return v_result;
end;
$$;

create table if not exists public.device_tokens (
    id uuid primary key default gen_random_uuid(),
    user_id uuid not null references auth.users(id) on delete cascade,
    fcm_token text not null unique,
    platform text not null default 'android',
    updated_at timestamptz not null default now(),
    created_at timestamptz not null default now()
);

create index if not exists device_tokens_user_idx
on public.device_tokens(user_id);

alter table public.profiles enable row level security;
alter table public.device_tokens enable row level security;
alter table public.chats enable row level security;
alter table public.chat_members enable row level security;
alter table public.messages enable row level security;
alter table public.gift_catalog enable row level security;
alter table public.user_gifts enable row level security;
alter table public.monogram_instance enable row level security;

create policy "Users manage their own push tokens"
on public.device_tokens for all to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

create policy "Profiles are visible to signed-in users"
on public.profiles for select to authenticated
using (true);

create policy "Users update their own profile"
on public.profiles for update to authenticated
using (user_id = auth.uid())
with check (user_id = auth.uid());

create policy "Members can view their chats"
on public.chats for select to authenticated
using (public.monogram_is_chat_member(chats.id));

create policy "Members can view chat membership"
on public.chat_members for select to authenticated
using (public.monogram_is_chat_member(chat_members.chat_id));

create policy "Chat members can read messages"
on public.messages for select to authenticated
using (public.monogram_is_chat_member(messages.chat_id));

create policy "Chat members can send messages as themselves"
on public.messages for insert to authenticated
with check (
    sender_id = auth.uid()
    and public.monogram_is_chat_member(messages.chat_id)
);

create policy "Gift catalog is readable"
on public.gift_catalog for select to authenticated
using (enabled = true);

create policy "Users can view gifts they own"
on public.user_gifts for select to authenticated
using (owner_id = auth.uid());

create policy "MONOGRAM settings are not directly readable"
on public.monogram_instance for select to authenticated
using (false);

grant select on public.profiles, public.gift_catalog, public.user_gifts to authenticated;
grant select, insert, update, delete on public.device_tokens to authenticated;
grant update (username, first_name, last_name, avatar_url, customization, updated_at)
on public.profiles to authenticated;
grant select on public.chats, public.chat_members, public.messages to authenticated;
grant insert (chat_id, sender_id, body, media_url) on public.messages to authenticated;
grant execute on function public.claim_monogram_gift(text) to authenticated;
grant execute on function public.create_direct_chat(uuid) to authenticated;

-- Private media storage. Object names must start with the authenticated user UUID.
insert into storage.buckets(id, name, public)
values ('monogram-media', 'monogram-media', false)
on conflict (id) do nothing;

create policy "MONOGRAM users upload their own media"
on storage.objects for insert to authenticated
with check (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

create policy "MONOGRAM users read their own media"
on storage.objects for select to authenticated
using (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

create policy "MONOGRAM users update their own media"
on storage.objects for update to authenticated
using (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
)
with check (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

create policy "MONOGRAM users delete their own media"
on storage.objects for delete to authenticated
using (
    bucket_id = 'monogram-media'
    and (storage.foldername(name))[1] = auth.uid()::text
);

-- Enable Supabase Realtime for message updates.
do $$
begin
    alter publication supabase_realtime add table public.messages;
exception when duplicate_object then
    null;
end;
$$;
