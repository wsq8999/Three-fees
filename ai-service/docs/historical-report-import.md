# 历史报告批量导入规范

## 与材料上传的关系

材料上传用于业务员日常新增单份报告或截图；历史报告批量导入用于项目初始化时把既有目录一次性迁入。两者最终都生成 `source_document`，使用同一套城市隔离、文件校验、SHA-256 去重、受控存储、审计日志和解析服务。

批量导入不会根据正文猜城市，只读取一级城市文件夹名称。无法映射城市或扩展名不受支持的文件不会写库。

## 执行方式

先预演，确认发现数量后再正式执行：

```powershell
.\backend\.venv\Scripts\python.exe .\scripts\import_historical_reports.py "C:\历史报告目录"
.\backend\.venv\Scripts\python.exe .\scripts\import_historical_reports.py "C:\历史报告目录" --apply
```

- 缺少 `--apply` 时只扫描目录，不复制文件、不写数据库。
- 每次运行都会在 `data/import-results` 生成 JSON 清单。
- 相同城市、材料类型和文件内容再次导入时标记为 `skipped`，不会重复入库。
- 原始目录始终只读；系统把文件复制到自己的受控存储。

## 当前解析边界

- `.docx`：按原文顺序提取标题、段落、表格和图片。
- `.doc`：保留原文件，解析记录为 `legacy_doc_conversion_required`。安装 LibreOffice 并实现受控转换后再重试。
- 图片元素：当前只提取原图，尚未做 OCR 或视觉理解。
- 解析失败不会删除原始文件，也不会伪造成功结果。

## 历史报告报账点回填

首次导入的27份历史材料已经人工复核正文标题，并通过独立脚本创建报账点和关联 `site_id`：

```powershell
.\backend\.venv\Scripts\python.exe .\scripts\backfill_historical_report_sites.py
.\backend\.venv\Scripts\python.exe .\scripts\backfill_historical_report_sites.py --apply
```

- 缺省只预演，`--apply` 才写入数据库。
- 脚本固定覆盖常州6份、苏州11份、镇江5份、泰州5份，共27份。
- DOCX名称取自报告正文标题；泰州旧DOC名称通过本机Word只读提取后人工复核。
- 材料未提供正式业务编码，因此暂用 `HIST-...` 稳定占位编码，并在 `site.metadata.business_code_status` 标记为 `unknown`。
- 执行会记录 `site.created_from_history` 和 `document.site_updated` 审计日志。
- 重复执行会跳过已有正确关联，不创建重复报账点。
- 任一材料缺失或已有冲突关联时整批回滚，复核清单保存在 `data/import-results`。
