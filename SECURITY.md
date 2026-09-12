# Security policy

## Reporting a vulnerability

脆弱性は公開Issueではなく、GitHubのPrivate vulnerability reportingから報告してください。再現に必要な最小限の情報だけを含め、ADB秘密鍵、ペア設定コード、端末の画面内容は送らないでください。

## Sensitive data

ZFoldDuoは端末内ADBの秘密鍵をアプリ専用領域へ保存します。画面フレームはメモリ上だけで処理し、永続化しません。デバッグログへ画面内容や鍵を出力する変更は受け付けません。

## Supported versions

セキュリティ修正は最新リリースへ提供します。Samsungの内部実装へ依存するため、OS更新後に安全な動作を確認できない場合は該当ビルドを未対応として扱います。
