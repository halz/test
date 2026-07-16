# MC Quote 登録 → 承認待ち化 — アクションステップ（レビュー用ドラフト）

> 出典動画: `1002261a-MCHIA.mp4`（3分56秒 / 852x480）
> 内容: ヘルプデスクの Quote Request チケットに届いた見積（Quote）を、MC システム
> （Maintenance Connection / mc.unitedpetroleum.com.au）に Work Order として起票し、
> 承認待ち（Requested — Level 1）状態にするまでの一連の操作。
> このドラフトはスキル化前のレビュー用。低解像度のため読み取りが不確かな箇所は「❓」を付けている。

## 動画で扱われていた具体例

| 項目 | 値 |
|---|---|
| チケット | Quote Request - Hillside |
| 見積番号 | QU019575（Landmark Computers / LMC 発行） |
| 品目 | Lenovo M70Q G5 Tiny Desktop（Core i5-14400T / 16GB / 512GB SSD）×1 |
| 金額 | 品代 $1,429.00 + 送料 $18.18 = **合計 $1,449.00 AUD**（GST込） |
| 用途 | Back Office PC の交換（Replacement for Back Office PC） |
| サイト | Hillside（VIC） |

---

## フェーズ 1: 見積内容の確認（ヘルプデスク側）

1. ヘルプデスクで対象チケット（例: 「Quote Request - Hillside」）を開く。
2. チケット上部の見積サマリ（QUOTES ANALYSIS ❓）で以下を確認する:
   - 見積番号（QU019575）とステータスバッジ
   - サイト名・住所（United Petroleum, Hillside VIC）
   - 品目・数量・単価・合計金額（1,449.00 AUD）
3. 添付ファイル一覧から見積 PDF（QU019575.pdf）を開く。
4. PDF の記載内容を確認する: 見積番号 / 発行日・有効期限（Due Date）/ Bill To・Ship To /
   品目・型番・数量・単価 / Total Amount Due incl. GST / 送料 / 条件（T&C）。
5. 見積 PDF をローカルにダウンロードする（後で MC の Work Order に添付するため）。
   - 動画では Adobe Acrobat でも開いて内容を再確認している。
   - ❓ 動画序盤で StarTrack の POD（Proof of Delivery）PDF も閲覧しているが、
     これが本タスクの一部（過去納品の確認）か別作業の名残かは要確認。

## フェーズ 2: MC（Maintenance Connection）へログイン

6. ブラウザで `mc.unitedpetroleum.com.au` を開く。
7. Maintenance Connection のログイン画面で Member ID とパスワードを入力してログインする
   （動画ではブラウザ保存済みの資格情報を使用）。
8. ダッシュボード表示後、Work Orders モジュールに移動する。

## フェーズ 3: Work Order 新規作成 — Details タブ

9. New Work Order を開く（画面上部に「Please click SAVE or CANCEL when you are finished」と表示される）。
10. Reason / Description 欄に内容を入力する。動画の記載例:
    ```
    1* Lenovo M70Q G5 Tiny Desktop [77T3S062BA62] Intel Core i5-14400T/16GB/512GB
    - Replacement for Back Office PC
    - Requested by KOM ❓
    ```
    （品名＋型番、目的、依頼元の3点セット）
11. Requester 情報を入力/確認する: Requester ID（YOSHIADACHI ❓）、Name、Phone、Email。
    - ❓ Contractor's Job Tracking No に見積番号を入れるかは要確認（動画では空欄に見える）。
12. Asset / Location をツリーから選択する:
    `Retail → VIC → VIC Retail Sites ❓ → Hillside → IT Systems → Back Office Computer (SPP16064 ❓)`
    - 選択すると資産の写真と Physical Location（521-599 Melton Hwy, Hillside VIC 3037 ❓）が表示される。
13. 中央の Details パネルの各フィールドを設定する:
    - Target Date（目標日）
    - Work Type: **CAPEX（Capital Expenditure）** ❓
    - Category / Account（3274 ❓）/ Priority（P3 ❓）
    - Repair Center / Shop / Costs To Be Charged / Department（IT ❓）/ Supervisor
    - Taken By: 自分（YOSHIADACHI ❓）
    - ❓ 各ドロップダウンの値の決め方（サイト・費目ごとのルール）は要ヒアリング

## フェーズ 4: Tasks タブ — タスクと見積金額の入力

14. Tasks タブで Add Task をクリックし、タスク行を追加する。
15. Description にフェーズ3と同様の内容（品目・交換作業）を入力する。
16. Estimated（見積金額）欄に見積合計額（$1,449.00 ❓）を入力する。
    → 保存後、右パネルの Total Estimated Cost に反映される。

## フェーズ 5: Attach タブ — 見積 PDF の添付

17. Attach タブ → Documents → New... をクリックする。
18. New Document ダイアログでフェーズ1でダウンロードした見積 PDF（QU019575.pdf）を
    アップロードし、Close で閉じる。
    - ❓ Repair Center の選択（UP General ❓）がダイアログ内にあるが既定値のままか要確認。

## フェーズ 6: Assign タブ — 業者（Labor)の割り当て

19. Assign タブを開き、Labor フィルタに業者名（例: `landmark`）を入力して絞り込む。
20. 該当業者「LANDMARK COMPUTERS (IT Systems Services)」を選択し、月間カレンダーを表示する。
21. カレンダー上の割当日をクリックし、New Assignment ダイアログを開く。
22. 内容（Work Order 番号、Craft: IT Systems Services、Date、Assigned Load /
    Assigned PDA Load のチェック ❓）を確認して Save する。
    - ❓ 割当日の決め方（見積の Due Date 基準? 業者の空き基準?）は要確認。

## フェーズ 7: 承認者設定・保存（承認待ち化）

23. Details タブに戻り、「To be approved by」フィールドの lookup から承認者を選択する。
    - ❓ 承認者の選定ルール（金額・部門による承認レベル）は要確認。
24. ツールバーの **Save** をクリックして Work Order を保存する。
25. 完了確認:
    - 右パネルの Status が **Requested**（日付付き）になっている
    - Approval 欄が **Level 1（承認待ち）** になっている ❓
    - Assignments に業者（LANDMARK COMP...）が表示されている
    - Total Estimated Cost が見積金額（$1,449.00）と一致している

---

## レビューで確認したい点

1. ヘルプデスク側の後処理（チケットのステータス変更、WO番号の返信・コメント記入など）は
   必要か。動画では確認できなかった。
2. POD（StarTrack Proof of Delivery）閲覧はこのタスクの標準手順に含まれるか。
3. Details タブの各ドロップダウン（Work Type / Category / Account / Priority /
   Repair Center / Shop / Department 等）の正確な値と選定ルール。
4. 「To be approved by」承認者の選び方（固定? 金額ベース?）。
5. Contractor's Job Tracking No への見積番号記入の要否。
6. Assign の割当日の決め方。
7. 低解像度のため ❓ 付きの読み取り（ID・コード類）が正しいか。
