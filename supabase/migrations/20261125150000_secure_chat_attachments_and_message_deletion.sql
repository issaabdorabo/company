insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values (
  'chat-attachments',
  'chat-attachments',
  false,
  20971520,
  array[
    'image/jpeg',
    'image/png',
    'image/webp',
    'image/gif',
    'application/pdf',
    'audio/mp4',
    'audio/aac',
    'audio/ogg',
    'audio/mpeg',
    'audio/webm',
    'audio/3gpp'
  ]
)
on conflict (id) do update
set public = false,
    file_size_limit = excluded.file_size_limit,
    allowed_mime_types = excluded.allowed_mime_types;

drop policy if exists chat_attachments_read on storage.objects;
create policy chat_attachments_read
on storage.objects for select
to authenticated
using (
  bucket_id = 'chat-attachments'
  and exists (
    select 1
    from public.conversations c
    where c.id::text = split_part(storage.objects.name, '/', 1)
      and (
        private.is_conversation_participant(c.id)
        or private.is_workspace_owner(c.workspace_id)
      )
  )
);

drop policy if exists chat_attachments_upload on storage.objects;
create policy chat_attachments_upload
on storage.objects for insert
to authenticated
with check (
  bucket_id = 'chat-attachments'
  and split_part(storage.objects.name, '/', 2) = (select auth.uid())::text
  and exists (
    select 1
    from public.conversations c
    where c.id::text = split_part(storage.objects.name, '/', 1)
      and (
        private.is_conversation_participant(c.id)
        or private.is_workspace_owner(c.workspace_id)
      )
  )
);

drop policy if exists chat_attachments_delete on storage.objects;
create policy chat_attachments_delete
on storage.objects for delete
to authenticated
using (
  bucket_id = 'chat-attachments'
  and split_part(storage.objects.name, '/', 2) = (select auth.uid())::text
  and exists (
    select 1
    from public.conversations c
    where c.id::text = split_part(storage.objects.name, '/', 1)
      and (
        private.is_conversation_participant(c.id)
        or private.is_workspace_owner(c.workspace_id)
      )
  )
);

create or replace function public.delete_message(p_message uuid)
returns void
language plpgsql
security definer
set search_path = ''
as $function$
declare
  v_conversation uuid;
  v_sender uuid;
  v_workspace uuid;
begin
  if auth.uid() is null then
    raise exception 'AUTH_REQUIRED';
  end if;

  select m.conversation_id, m.sender_user_id, c.workspace_id
    into v_conversation, v_sender, v_workspace
  from public.messages m
  join public.conversations c on c.id = m.conversation_id
  where m.id = p_message;

  if not found then
    return;
  end if;

  if v_sender <> auth.uid() then
    raise exception 'MESSAGE_DELETE_FORBIDDEN';
  end if;

  if not private.is_conversation_participant(v_conversation)
     and not private.is_workspace_owner(v_workspace) then
    raise exception 'NOT_CONVERSATION_PARTICIPANT';
  end if;

  delete from public.messages where id = p_message;
end;
$function$;

revoke all on function public.delete_message(uuid) from public, anon;
grant execute on function public.delete_message(uuid) to authenticated;
