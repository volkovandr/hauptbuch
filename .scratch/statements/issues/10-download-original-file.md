# Statement page: download the original file back

Status: resolved

## Symptom
The uploaded CSV/PDF is kept in the statement storage (`statement.file_path`) but cannot be
retrieved from the UI.

## Accepted solution (owner, 2026-10-10)
- On the statement page the caption's file name (after the profile name) becomes a link that
  downloads the stored file under its original file name.
- `GET /statements/{id}/file` serves the bytes as an attachment
  (`application/octet-stream`, `Content-Disposition: attachment`). A missing statement or a missing
  stored file answers 404.
