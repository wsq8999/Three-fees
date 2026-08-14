from io import BytesIO
from uuid import UUID, uuid4
from zipfile import ZIP_DEFLATED, ZipFile

from sqlalchemy import select

from app.modules.audit_logs.model import AuditLogModel
from app.modules.memory_governance.model import MemoryRevisionModel
from app.modules.sites.model import SiteModel


def _create_site(db_session, city_id: int, name: str) -> str:
    """测试显式创建报账点主数据，任务接口本身不再偷偷创建。"""
    site_id = uuid4()
    db_session.add(
        SiteModel(
            id=site_id,
            city_id=city_id,
            site_code=f"TEST-{site_id.hex[:12].upper()}",
            site_name=name,
            status="active",
            extra_metadata={},
        )
    )
    db_session.commit()
    return str(site_id)


def _minimal_docx_bytes() -> bytes:
    """构造包含标题、正文、表格和图片的最小OOXML测试文档。"""
    document_xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"
 xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
 xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main">
  <w:body>
    <w:p><w:pPr><w:pStyle w:val="Title"/></w:pPr>
      <w:r><w:t>苏州测试稽核报告</w:t></w:r></w:p>
    <w:p><w:r><w:t>情况说明</w:t></w:r></w:p>
    <w:p><w:r><w:t>本期用电同比超标。</w:t></w:r>
      <w:r><w:drawing><a:blip r:embed="rId1"/></w:drawing></w:r></w:p>
    <w:tbl><w:tr>
      <w:tc><w:p><w:r><w:t>指标</w:t></w:r></w:p></w:tc>
      <w:tc><w:p><w:r><w:t>同比</w:t></w:r></w:p></w:tc>
    </w:tr></w:tbl>
    <w:sectPr/>
  </w:body>
</w:document>""".encode()
    relationships_xml = b"""<?xml version="1.0" encoding="UTF-8"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1"
    Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/image"
    Target="media/image1.png"/>
</Relationships>"""
    styles_xml = b"""<?xml version="1.0" encoding="UTF-8"?>
<w:styles xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">
  <w:style w:type="paragraph" w:styleId="Title"><w:name w:val="Title"/></w:style>
</w:styles>"""
    content_types_xml = b"""<?xml version="1.0" encoding="UTF-8"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="xml" ContentType="application/xml"/>
  <Default Extension="png" ContentType="image/png"/>
</Types>"""
    output = BytesIO()
    with ZipFile(output, "w", ZIP_DEFLATED) as archive:
        archive.writestr("[Content_Types].xml", content_types_xml)
        archive.writestr("word/document.xml", document_xml)
        archive.writestr("word/_rels/document.xml.rels", relationships_xml)
        archive.writestr("word/styles.xml", styles_xml)
        archive.writestr("word/media/image1.png", b"\x89PNG\r\n\x1a\nparsed-image")
    return output.getvalue()


def test_health_and_cities_are_public(client) -> None:
    assert client.get("/api/v1/health/live").json() == {"status": "ok"}
    assert client.get("/api/v1/health/ready").json() == {
        "status": "ok",
        "database": "ok",
    }
    cities = client.get("/api/v1/cities").json()["items"]
    assert len(cities) == 13
    assert {item["code"] for item in cities} >= {"nanjing", "suzhou"}


def test_business_endpoint_requires_city_context(client) -> None:
    response = client.get("/api/v1/audit-tasks")

    assert response.status_code == 400
    assert response.headers["content-type"].startswith("application/problem+json")
    assert response.json()["code"] == "city_context_required"


def test_create_task_and_run_evidence_agent(client, db_session) -> None:
    headers = {"X-City-Code": "suzhou"}
    site_id = _create_site(db_session, 5, "苏州工业园区测试报账点")
    task_response = client.post(
        "/api/v1/audit-tasks",
        headers=headers,
        json={
            "site_id": site_id,
            "title": "真实证据联调任务",
            "question": "检索历史证据，但不生成未经识别的原因",
        },
    )
    assert task_response.status_code == 201
    task_id = task_response.json()["id"]

    missing_material = client.post(
        f"/api/v1/audit-tasks/{task_id}/analysis-runs",
        headers=headers,
        json={"material_refs": []},
    )
    assert missing_material.status_code == 400
    assert missing_material.json()["code"] == "current_material_required"

    screenshot = client.post(
        "/api/v1/documents",
        headers=headers,
        data={
            "title": "本次电费超标截图",
            "document_type": "evidence_screenshot",
            "task_id": task_id,
        },
        files={"file": ("evidence.png", b"\x89PNG\r\n\x1a\nagent", "image/png")},
    ).json()

    run_response = client.post(
        f"/api/v1/audit-tasks/{task_id}/analysis-runs",
        headers=headers,
        json={"material_refs": [screenshot["id"]]},
    )
    assert run_response.status_code == 202
    assert run_response.headers["location"].startswith("/api/v1/analysis-runs/")
    run_id = run_response.json()["id"]

    completed = client.get(f"/api/v1/analysis-runs/{run_id}", headers=headers)
    assert completed.status_code == 200
    body = completed.json()
    assert body["status"] == "completed"
    assert body["result"]["mode"] == "report_draft_ready"
    assert body["result"]["current_materials"][0]["document_id"] == screenshot["id"]
    assert body["result"]["retrieval_summary"]["selected_history_count"] == 0
    assert body["result"]["facts"]["status"] == "completed"
    assert body["result"]["facts"]["model"] == "fake-vision-model"
    metric = body["result"]["facts"]["documents"][0]["metrics"][0]
    assert metric["actual_value"] == "120.50"
    assert metric["evidence_text"] == "日均电费同比20.5%"
    assert body["result"]["judgment"]["primary_reason"] is None


def test_analysis_interrupt_resume_is_city_scoped_and_single_use(client, db_session) -> None:
    """不明确状态会暂停；只能在原城市恢复一次，并继续原运行而不是新建运行。"""
    headers = {"X-City-Code": "suzhou"}
    site_id = _create_site(db_session, 5, "苏州人工中断测试报账点")
    task = client.post(
        "/api/v1/audit-tasks",
        headers=headers,
        json={"site_id": site_id, "title": "人工中断测试"},
    ).json()
    screenshot = client.post(
        "/api/v1/documents",
        headers=headers,
        data={
            "title": "总体状态不清晰截图",
            "document_type": "evidence_screenshot",
            "task_id": task["id"],
        },
        files={"file": ("unknown.png", b"\x89PNG\r\n\x1a\nagent", "image/png")},
    ).json()
    accepted = client.post(
        f"/api/v1/audit-tasks/{task['id']}/analysis-runs",
        headers=headers,
        json={"material_refs": [screenshot["id"]]},
    )
    assert accepted.status_code == 202
    run_id = accepted.json()["id"]

    waiting = client.get(f"/api/v1/analysis-runs/{run_id}", headers=headers).json()
    assert waiting["status"] == "waiting_input"
    assert waiting["pending_interrupt"]["type"] == "confirm_system_over_limit"
    assert waiting["pending_interrupt"]["screening"]["status"] == "unknown"

    # 其他城市既看不到运行，也不能借恢复接口探测或推进苏州检查点。
    other_city = {"X-City-Code": "nanjing"}
    assert client.get(f"/api/v1/analysis-runs/{run_id}", headers=other_city).status_code == 404
    cross_city_resume = client.post(
        f"/api/v1/analysis-runs/{run_id}/resume",
        headers=other_city,
        json={"decision": "confirm_over_limit"},
    )
    assert cross_city_resume.status_code == 404

    resumed = client.post(
        f"/api/v1/analysis-runs/{run_id}/resume",
        headers=headers,
        json={"decision": "confirm_over_limit", "note": "人工核对系统页面"},
    )
    assert resumed.status_code == 202
    completed = client.get(f"/api/v1/analysis-runs/{run_id}", headers=headers).json()
    assert completed["id"] == run_id
    assert completed["status"] == "completed"
    assert completed["pending_interrupt"] is None
    assert completed["result"]["screening"]["source"] == "human_confirmation"
    assert completed["result"]["mode"] == "report_draft_ready"

    repeated = client.post(
        f"/api/v1/analysis-runs/{run_id}/resume",
        headers=headers,
        json={"decision": "confirm_over_limit"},
    )
    assert repeated.status_code == 409


def test_langgraph_retrieves_only_linked_same_site_history(client, db_session) -> None:
    """本次DOCX的全部元素参与识别，同时历史报告只检索同点证据。"""
    headers = {"X-City-Code": "suzhou"}
    site_id = _create_site(db_session, 5, "苏州园区主流程报账点")
    task = client.post(
        "/api/v1/audit-tasks",
        headers=headers,
        json={"site_id": site_id, "title": "主流程任务"},
    ).json()
    current_report = client.post(
        "/api/v1/documents",
        headers=headers,
        data={
            "title": "本次待分析报告",
            "document_type": "current_report",
            "task_id": task["id"],
        },
        files={
            "file": (
                "current.docx",
                _minimal_docx_bytes(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )
        },
    ).json()
    parsed_current = client.post(
        f"/api/v1/documents/{current_report['id']}/parse-runs",
        headers=headers,
    )
    assert parsed_current.json()["status"] == "completed"
    history = client.post(
        "/api/v1/documents",
        headers=headers,
        data={"title": "该报账点历史稽核报告", "document_type": "historical_report"},
        files={
            "file": (
                "same-site.docx",
                _minimal_docx_bytes(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )
        },
    ).json()
    parsed = client.post(f"/api/v1/documents/{history['id']}/parse-runs", headers=headers)
    assert parsed.json()["status"] == "completed"

    linked = client.patch(
        f"/api/v1/documents/{history['id']}",
        headers=headers,
        json={"site_id": current_report["site_id"]},
    )
    assert linked.status_code == 200
    assert linked.json()["site_id"] == current_report["site_id"]

    # 历史报告先形成可复用案例；证据元素ID能追溯回原Word。
    case_response = client.post(
        "/api/v1/audit-cases",
        headers=headers,
        json={"source_document_id": history["id"]},
    )
    assert case_response.status_code == 201
    assert case_response.json()["status"] == "ready"
    assert case_response.json()["evidence_element_ids"]

    accepted = client.post(
        f"/api/v1/audit-tasks/{task['id']}/analysis-runs",
        headers=headers,
        json={"material_refs": [current_report["id"]]},
    )
    run_id = accepted.json()["id"]
    completed = client.get(f"/api/v1/analysis-runs/{run_id}", headers=headers)
    assert completed.status_code == 200
    result = completed.json()["result"]
    assert result["mode"] == "report_draft_ready"
    assert result["current_materials"][0]["element_count"] == 5
    assert result["current_materials"][0]["text_element_count"] == 4
    assert result["current_materials"][0]["image_element_count"] == 1
    report_facts = result["facts"]["documents"][0]
    assert report_facts["document_type"] == "current_report"
    assert report_facts["document_summary"]
    assert report_facts["metrics"][0]["evidence_element_ids"]
    assert result["retrieval_summary"]["scope"] == "same_site"
    assert result["retrieval_summary"]["selected_history_count"] == 1
    assert result["calculations"]["verified_count"] == 1
    assert result["calculations"]["conflict_count"] == 0
    assert result["calculations"]["metrics"][0]["calculated_over_limit_rate_percent"] == "20.5"
    assert result["report_draft"]["status"] == "needs_review"
    assert len(result["report_draft"]["sections"]) == 6
    assert result["report_draft"]["prompt_version"] == "draft_report_v2"
    assert [item["document_id"] for item in result["evidence"]] == [history["id"]]
    assert result["evidence"][0]["match_scope"] == "same_site"
    assert result["evidence"][0]["audit_case"]["primary_reason"]
    assert any(
        element["content_text"] == "本期用电同比超标。"
        for element in result["evidence"][0]["elements"]
    )

    # AI运行结果只读；显式创建报告后，正文才进入人工编辑、版本和审核流程。
    created_report = client.post(f"/api/v1/analysis-runs/{run_id}/reports", headers=headers)
    assert created_report.status_code == 201
    report = created_report.json()
    report_id = report["id"]
    assert report["status"] == "draft"
    assert report["current_version"] == 1
    assert len(report["current"]["sections"]) == 6
    # 创建接口幂等，页面重复点击不会生成两份报告。
    assert (
        client.post(f"/api/v1/analysis-runs/{run_id}/reports", headers=headers).json()["id"]
        == report_id
    )

    edited_sections = [
        {
            "section_code": section["section_code"],
            "title": section["title"],
            "content": f"人工复核后的{section['title']}正文。",
        }
        for section in report["current"]["sections"]
    ]
    saved = client.patch(
        f"/api/v1/reports/{report_id}",
        headers=headers,
        json={
            "title": "苏州测试报账点电费超标稽核报告",
            "sections": edited_sections,
            "change_summary": "人工核对本次截图并修订正文",
        },
    )
    assert saved.status_code == 200
    assert saved.json()["current_version"] == 2
    assert len(saved.json()["versions"]) == 2
    # 页面不能伪造或删除证据链，编辑后引用由服务端从上一版本继承。
    assert (
        saved.json()["current"]["sections"][0]["supporting_current_element_ids"]
        == report["current"]["sections"][0]["supporting_current_element_ids"]
    )

    submitted = client.post(
        f"/api/v1/reports/{report_id}/reviews",
        headers=headers,
        json={"action": "submit"},
    )
    assert submitted.json()["status"] == "in_review"
    returned = client.post(
        f"/api/v1/reports/{report_id}/reviews",
        headers=headers,
        json={"action": "return", "note": "请补充整改结果"},
    )
    assert returned.json()["status"] == "returned"
    assert returned.json()["review_note"] == "请补充整改结果"

    revised_sections = returned.json()["current"]["sections"]
    revised_sections[-1]["content"] = "已完成设备用电复核并持续观察。"
    version_three = client.patch(
        f"/api/v1/reports/{report_id}",
        headers=headers,
        json={
            "title": returned.json()["current"]["title"],
            "sections": [
                {
                    "section_code": section["section_code"],
                    "title": section["title"],
                    "content": section["content"],
                }
                for section in revised_sections
            ],
            "change_summary": "根据退回意见补充整改小结",
        },
    ).json()
    assert version_three["current_version"] == 3
    assert version_three["status"] == "draft"
    client.post(
        f"/api/v1/reports/{report_id}/reviews",
        headers=headers,
        json={"action": "submit"},
    )
    approved = client.post(
        f"/api/v1/reports/{report_id}/reviews",
        headers=headers,
        json={"action": "approve", "note": "审核通过"},
    )
    assert approved.status_code == 200
    assert approved.json()["status"] == "approved"
    assert approved.json()["approved_version"] == 3
    assert approved.json()["content_url"].endswith("/content")

    official_word = client.get(f"/api/v1/reports/{report_id}/content", headers=headers)
    assert official_word.status_code == 200
    with ZipFile(BytesIO(official_word.content)) as archive:
        # 原始图片和关系部件仍然存在，AI正文只追加到document.xml。
        assert archive.read("word/media/image1.png") == b"\x89PNG\r\n\x1a\nparsed-image"
        formal_xml = archive.read("word/document.xml").decode("utf-8")
    assert "苏州测试报账点电费超标稽核报告" in formal_xml
    assert "已完成设备用电复核并持续观察" in formal_xml
    assert (
        client.get(f"/api/v1/reports/{report_id}", headers={"X-City-Code": "nanjing"}).status_code
        == 404
    )
    assert (
        client.get(
            f"/api/v1/reports/{report_id}/content",
            headers={"X-City-Code": "nanjing"},
        ).status_code
        == 404
    )

    # 业务员原话先生成draft；未确认前不会进入下一次Agent上下文。
    correction = client.post(
        f"/api/v1/analysis-runs/{run_id}/correction-memories",
        headers=headers,
        json={"message": "你这次判断错了，实际是新增通信设备扩容导致用电量增长。"},
    )
    assert correction.status_code == 201
    draft = correction.json()
    assert draft["status"] == "draft"
    assert draft["memory_status"] == "paused"
    assert draft["corrected_reason"] == "新增通信设备扩容导致用电量增长"

    confirmed = client.patch(
        f"/api/v1/correction-memories/{draft['id']}",
        headers=headers,
        json={"status": "confirmed"},
    )
    assert confirmed.status_code == 200
    assert confirmed.json()["status"] == "confirmed"
    assert confirmed.json()["memory_status"] == "active"
    assert (
        client.get(
            f"/api/v1/correction-memories/{draft['id']}",
            headers={"X-City-Code": "nanjing"},
        ).status_code
        == 404
    )

    # 同城市同报账点再次运行时必须读取确认记忆，并由原因节点明确引用该记忆ID。
    rerun = client.post(
        f"/api/v1/audit-tasks/{task['id']}/analysis-runs",
        headers=headers,
        json={"material_refs": [current_report["id"]]},
    )
    rerun_id = rerun.json()["id"]
    corrected_result = client.get(f"/api/v1/analysis-runs/{rerun_id}", headers=headers).json()[
        "result"
    ]
    assert corrected_result["retrieval_summary"]["confirmed_correction_count"] == 1
    assert corrected_result["judgment"]["correction_memory_applied"] is True
    assert corrected_result["judgment"]["matched_correction_memory_ids"] == [draft["id"]]

    # 统一管理页同时看到历史案例和人工纠错，两者都使用相同的使用状态。
    memories = client.get("/api/v1/memories", headers=headers)
    assert memories.status_code == 200
    memory_items = memories.json()["items"]
    assert memories.json()["total"] == 2
    assert {item["memory_type"] for item in memory_items} == {
        "audit_case",
        "correction_memory",
    }
    assert {item["memory_status"] for item in memory_items} == {"active"}
    case_memory = next(item for item in memory_items if item["memory_type"] == "audit_case")

    # 标错是独立流程：创建后立即暂停案例，原始历史Word也不能旁路进入RAG。
    flagged = client.post(
        f"/api/v1/memories/audit_case/{case_memory['id']}/flags",
        headers=headers,
        json={"flag_type": "wrong_reason", "description": "历史报告的原因归纳不准确"},
    )
    assert flagged.status_code == 201
    flag_id = flagged.json()["id"]
    after_flag = client.get("/api/v1/memories?memory_type=audit_case", headers=headers).json()[
        "items"
    ][0]
    assert after_flag["memory_status"] == "paused"
    assert after_flag["open_flag_id"] == flag_id

    isolated_rerun = client.post(
        f"/api/v1/audit-tasks/{task['id']}/analysis-runs",
        headers=headers,
        json={"material_refs": [current_report["id"]]},
    ).json()
    isolated_result = client.get(
        f"/api/v1/analysis-runs/{isolated_rerun['id']}", headers=headers
    ).json()["result"]
    assert isolated_result["retrieval_summary"]["selected_history_count"] == 0

    # 误标复核恢复到标错前状态，不会制造新版本。
    dismissed = client.patch(
        f"/api/v1/memory-flags/{flag_id}",
        headers=headers,
        json={"resolution": "dismissed", "note": "复核后确认原记忆无误"},
    )
    assert dismissed.status_code == 200
    assert dismissed.json()["resolution_action"] == "restored"
    restored = client.get("/api/v1/memories?memory_type=audit_case", headers=headers).json()[
        "items"
    ][0]
    assert restored["memory_status"] == "active"

    # 再次标错并确认错误后，旧版本必须invalidated，且不能直接调用恢复接口。
    second_flag = client.post(
        f"/api/v1/memories/audit_case/{case_memory['id']}/flags",
        headers=headers,
        json={"flag_type": "wrong_reason", "description": "复核材料后确认原因确实错误"},
    ).json()
    invalidated = client.patch(
        f"/api/v1/memory-flags/{second_flag['id']}",
        headers=headers,
        json={"resolution": "invalidated", "note": "应为季节性空调用电"},
    )
    assert invalidated.status_code == 200
    direct_resume = client.patch(
        f"/api/v1/memories/audit_case/{case_memory['id']}/status",
        headers=headers,
        json={"memory_status": "active"},
    )
    assert direct_resume.status_code == 409

    # “修改并重新启用”先保存旧快照，再在稳定ID上产生下一版本。
    revised = client.post(
        f"/api/v1/memories/audit_case/{case_memory['id']}/revisions",
        headers=headers,
        json={
            "reason": "夏季持续高温导致空调用电增长",
            "reason_category": "季节性用电",
            "conditions": ["处于夏季高温期", "空调用电增长"],
            "change_reason": "依据复核后的原始截图修正",
        },
    )
    assert revised.status_code == 201
    revised_memory = revised.json()["memory"]
    assert revised_memory["memory_status"] == "active"
    assert revised_memory["reason"] == "夏季持续高温导致空调用电增长"
    assert revised_memory["version"] == revised.json()["replaced_version"] + 1
    revision = db_session.scalar(
        select(MemoryRevisionModel).where(
            MemoryRevisionModel.audit_case_id == UUID(case_memory["id"])
        )
    )
    assert revision is not None
    assert revision.snapshot["primary_reason"] != revised_memory["reason"]

    # 城市边界同样覆盖治理接口，南京不能标错或查看苏州的记忆。
    cross_city_flag = client.post(
        f"/api/v1/memories/audit_case/{case_memory['id']}/flags",
        headers={"X-City-Code": "nanjing"},
        json={"flag_type": "other", "description": "跨城市请求"},
    )
    assert cross_city_flag.status_code == 404

    actions = db_session.scalars(
        select(AuditLogModel.action).where(AuditLogModel.entity_id == history["id"])
    ).all()
    assert "document.site_updated" in actions


def test_city_context_cannot_read_another_city_task(client, db_session) -> None:
    site_id = _create_site(db_session, 1, "南京测试点")
    created = client.post(
        "/api/v1/audit-tasks",
        headers={"X-City-Code": "nanjing"},
        json={"site_id": site_id, "title": "南京任务"},
    )
    task_id = created.json()["id"]

    response = client.get(
        f"/api/v1/audit-tasks/{task_id}",
        headers={"X-City-Code": "suzhou"},
    )
    assert response.status_code == 404


def test_invalid_resource_ids_return_not_found(client) -> None:
    """错误格式的外部ID应得到稳定404，而不是触发数据库类型异常。"""
    headers = {"X-City-Code": "nanjing"}

    assert client.get("/api/v1/audit-tasks/not-a-uuid", headers=headers).status_code == 404
    assert client.get("/api/v1/analysis-runs/not-a-uuid", headers=headers).status_code == 404


def test_upload_list_download_and_archive_document(client, db_session) -> None:
    """材料必须完整经历上传、查询、下载和软归档。"""
    headers = {"X-City-Code": "suzhou"}
    site_id = _create_site(db_session, 5, "苏州材料测试点")
    task_response = client.post(
        "/api/v1/audit-tasks",
        headers=headers,
        json={"site_id": site_id, "title": "截图材料任务"},
    )
    task_id = task_response.json()["id"]

    uploaded = client.post(
        "/api/v1/documents",
        headers=headers,
        data={
            "title": "电费超标截图",
            "document_type": "evidence_screenshot",
            "task_id": task_id,
        },
        files={"file": ("evidence.png", b"\x89PNG\r\n\x1a\ncontent", "image/png")},
    )
    assert uploaded.status_code == 201
    document = uploaded.json()
    document_id = document["id"]
    assert uploaded.headers["location"] == f"/api/v1/documents/{document_id}"
    assert document["task_id"] == task_id
    assert document["site_id"] is not None
    assert document["media_type"] == "image/png"
    assert document["ingestion_method"] == "manual_upload"
    assert "storage_key" not in document

    listed = client.get("/api/v1/documents", headers=headers).json()
    assert listed["total"] == 1
    assert listed["items"][0]["id"] == document_id

    downloaded = client.get(f"/api/v1/documents/{document_id}/content", headers=headers)
    assert downloaded.status_code == 200
    assert downloaded.content == b"\x89PNG\r\n\x1a\ncontent"

    # 同一城市同一材料类型不允许重复上传相同文件。
    duplicate = client.post(
        "/api/v1/documents",
        headers=headers,
        data={
            "title": "重复截图",
            "document_type": "evidence_screenshot",
            "task_id": task_id,
        },
        files={"file": ("copy.png", b"\x89PNG\r\n\x1a\ncontent", "image/png")},
    )
    assert duplicate.status_code == 409

    assert client.delete(f"/api/v1/documents/{document_id}", headers=headers).status_code == 204
    assert client.get(f"/api/v1/documents/{document_id}", headers=headers).status_code == 404
    assert client.get("/api/v1/documents", headers=headers).json()["total"] == 0

    # 上传和归档都必须写入只追加审计日志，使audit_log具有实际追踪价值。
    actions = db_session.scalars(
        select(AuditLogModel.action)
        .where(AuditLogModel.entity_id == document_id)
        .order_by(AuditLogModel.id)
    ).all()
    assert actions == ["document.uploaded", "document.archived"]


def test_document_city_isolation_and_file_validation(client) -> None:
    """材料读取遵守城市边界，上传内容必须与扩展名匹配。"""
    created = client.post(
        "/api/v1/documents",
        headers={"X-City-Code": "zhenjiang"},
        data={"title": "镇江历史报告", "document_type": "historical_report"},
        files={"file": ("history.pdf", b"%PDF-1.7\ncontent", "application/pdf")},
    )
    assert created.status_code == 201
    document_id = created.json()["id"]

    cross_city = client.get(f"/api/v1/documents/{document_id}", headers={"X-City-Code": "suzhou"})
    assert cross_city.status_code == 404

    disguised = client.post(
        "/api/v1/documents",
        headers={"X-City-Code": "zhenjiang"},
        data={"title": "伪装文件", "document_type": "historical_report"},
        files={"file": ("broken.pdf", b"not-a-pdf", "application/pdf")},
    )
    assert disguised.status_code == 400
    assert disguised.json()["code"] == "invalid_file_content"


def test_site_and_document_type_dropdowns_come_from_backend(client, db_session) -> None:
    """前端业务下拉框通过后端字典和数据库查询获得。"""
    headers = {"X-City-Code": "changzhou"}
    _create_site(db_session, 4, "常州下拉框测试点")

    sites = client.get("/api/v1/sites?keyword=下拉框", headers=headers).json()
    assert sites["total"] == 1
    assert sites["items"][0]["site_name"] == "常州下拉框测试点"

    types = client.get("/api/v1/document-types").json()["items"]
    assert {item["code"] for item in types} == {
        "historical_report",
        "current_report",
        "evidence_screenshot",
        "report_template",
    }


def test_create_site_is_city_scoped_and_starts_without_memory(client, db_session) -> None:
    """页面新增报账点应自动生成编码，并且不能顺带制造历史记忆。"""
    suzhou_headers = {"X-City-Code": "suzhou"}
    created = client.post(
        "/api/v1/sites",
        headers=suzhou_headers,
        json={"site_name": "苏州首次分析测试资源点", "address": "苏州市测试地址"},
    )

    assert created.status_code == 201
    site = created.json()
    assert created.headers["location"] == f"/api/v1/sites/{site['id']}"
    assert site["city_code"] == "suzhou"
    assert site["site_code"].startswith("SITE-SUZHOU-")
    assert site["site_name"] == "苏州首次分析测试资源点"
    assert site["status"] == "active"

    # 新站点能立即被当前城市下拉框搜索到，其他城市无法看到。
    suzhou_list = client.get("/api/v1/sites?keyword=首次分析", headers=suzhou_headers).json()
    assert [item["id"] for item in suzhou_list["items"]] == [site["id"]]
    nanjing_list = client.get(
        "/api/v1/sites?keyword=首次分析", headers={"X-City-Code": "nanjing"}
    ).json()
    assert nanjing_list["total"] == 0

    # 创建主数据不会创建案例或纠错；首次分析前该报账点确实是零记忆状态。
    assert (
        client.get(f"/api/v1/audit-cases?site_id={site['id']}", headers=suzhou_headers).json()[
            "items"
        ]
        == []
    )
    assert (
        client.get(
            f"/api/v1/correction-memories?site_id={site['id']}", headers=suzhou_headers
        ).json()["items"]
        == []
    )

    # 同城市重复名称必须提示用户选择已有记录，避免同一站点记忆被拆成多份。
    duplicate = client.post(
        "/api/v1/sites",
        headers=suzhou_headers,
        json={"site_name": " 苏州首次分析测试资源点 "},
    )
    assert duplicate.status_code == 409
    assert duplicate.json()["code"] == "resource_conflict"

    audit_action = db_session.scalar(
        select(AuditLogModel.action).where(
            AuditLogModel.entity_type == "site",
            AuditLogModel.entity_id == site["id"],
        )
    )
    assert audit_action == "site.created"


def test_docx_parse_run_extracts_ordered_text_table_and_image(client) -> None:
    """DOCX解析必须生成可审阅的有序元素并保护图片下载。"""
    headers = {"X-City-Code": "suzhou"}
    uploaded = client.post(
        "/api/v1/documents",
        headers=headers,
        data={"title": "可解析历史报告", "document_type": "historical_report"},
        files={
            "file": (
                "history.docx",
                _minimal_docx_bytes(),
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            )
        },
    )
    document_id = uploaded.json()["id"]

    parsed = client.post(f"/api/v1/documents/{document_id}/parse-runs", headers=headers)
    assert parsed.status_code == 201
    run = parsed.json()
    assert run["status"] == "completed"
    assert run["parser_name"] == "docx_ooxml"
    assert run["element_count"] == 5

    element_response = client.get(f"/api/v1/documents/{document_id}/elements", headers=headers)
    assert element_response.status_code == 200
    elements = element_response.json()["items"]
    assert [item["element_type"] for item in elements] == [
        "heading",
        "heading",
        "paragraph",
        "image",
        "table",
    ]
    assert elements[2]["content_text"] == "本期用电同比超标。"
    assert elements[4]["content_data"]["rows"] == [["指标", "同比"]]

    image_element = elements[3]
    image_response = client.get(image_element["asset_url"], headers=headers)
    assert image_response.status_code == 200
    assert image_response.content == b"\x89PNG\r\n\x1a\nparsed-image"
    assert (
        client.get(image_element["asset_url"], headers={"X-City-Code": "nanjing"}).status_code
        == 404
    )


def test_legacy_doc_parse_failure_is_persisted(client) -> None:
    """旧DOC无法转换时保留原件，并记录稳定失败原因。"""
    headers = {"X-City-Code": "taizhou"}
    uploaded = client.post(
        "/api/v1/documents",
        headers=headers,
        data={"title": "旧版历史报告", "document_type": "historical_report"},
        files={
            "file": (
                "legacy.doc",
                bytes.fromhex("D0CF11E0A1B11AE1") + b"legacy-content",
                "application/msword",
            )
        },
    )
    document_id = uploaded.json()["id"]

    parsed = client.post(f"/api/v1/documents/{document_id}/parse-runs", headers=headers)
    assert parsed.status_code == 201
    assert parsed.json()["status"] == "failed"
    assert parsed.json()["error_code"] == "legacy_doc_conversion_required"
    assert (
        client.get(f"/api/v1/documents/{document_id}", headers=headers).json()["status"] == "failed"
    )
