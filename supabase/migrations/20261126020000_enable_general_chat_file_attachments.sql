do $$
begin
  if not exists (select 1 from storage.buckets where id = 'chat-attachments') then
    raise exception 'CHAT_ATTACHMENTS_BUCKET_NOT_FOUND';
  end if;
end;
$$;

update storage.buckets
set allowed_mime_types = array[
  'image/jpeg','image/png','image/webp','image/gif',
  'application/pdf','audio/mp4','audio/aac','audio/ogg','audio/mpeg','audio/webm','audio/3gpp',
  'application/octet-stream','text/plain','text/csv','application/csv','text/markdown',
  'application/json','application/xml','text/xml','application/rtf','text/rtf',
  'application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.ms-excel',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.ms-powerpoint',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  'application/vnd.oasis.opendocument.text',
  'application/vnd.oasis.opendocument.spreadsheet',
  'application/vnd.oasis.opendocument.presentation',
  'application/zip','application/x-zip-compressed',
  'application/x-7z-compressed','application/vnd.rar','application/x-rar-compressed'
]
where id = 'chat-attachments';