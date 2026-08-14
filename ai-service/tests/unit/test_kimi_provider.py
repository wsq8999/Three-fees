"""Kimi适配器和截图事实Schema的离线单元测试。"""

import pytest
from langchain_core.messages import AIMessage

from app.agents.audit.facts import ExtractedMetric
from app.integrations.ai.base import AIProviderError
from app.integrations.ai.kimi import KimiProvider


class FakeRunnable:
    """记录LangChain绑定参数并返回预设最终内容。"""

    def __init__(self, content: str | list[str]) -> None:
        self.contents = [content] if isinstance(content, str) else content
        self.invoke_count = 0
        self.bound: dict[str, object] = {}
        self.bindings: list[dict[str, object]] = []

    def bind(self, **kwargs):
        self.bound = kwargs
        self.bindings.append(kwargs)
        return self

    def invoke(self, _):
        # 单值时所有调用都返回同一内容，便于验证两次无效响应的稳定错误映射。
        index = min(self.invoke_count, len(self.contents) - 1)
        self.invoke_count += 1
        return AIMessage(content=self.contents[index])


def test_kimi_provider_uses_strict_schema_and_final_content() -> None:
    """供应商适配器必须传递严格Schema并只解析最终content。"""
    provider = KimiProvider(
        api_key="test-key",
        base_url="https://example.invalid/v1",
        model="kimi-k3",
        timeout_seconds=1,
    )
    fake = FakeRunnable('{"value":"120.50"}')
    provider._client = fake

    result = provider.generate_structured(
        system_prompt="test",
        user_content=[{"type": "text", "text": "test"}],
        schema_name="test_schema",
        json_schema={"type": "object"},
        reasoning_effort="low",
    )

    assert result == {"value": "120.50"}
    assert fake.bound["reasoning_effort"] == "low"
    response_format = fake.bound["response_format"]
    assert response_format["json_schema"]["strict"] is True


def test_kimi_provider_rejects_invalid_json() -> None:
    """严格调用和JSON Mode都无效时返回不包含第三方正文的稳定错误。"""
    provider = KimiProvider(
        api_key="test-key",
        base_url="https://example.invalid/v1",
        model="kimi-k3",
        timeout_seconds=1,
    )
    provider._client = FakeRunnable("not-json")

    with pytest.raises(AIProviderError, match="不是有效的结构化JSON"):
        provider.generate_structured(
            system_prompt="test",
            user_content=[{"type": "text", "text": "test"}],
            schema_name="test_schema",
            json_schema={"type": "object"},
            reasoning_effort="low",
        )

    assert provider._client.invoke_count == 2


def test_kimi_provider_retries_invalid_strict_output_with_json_mode() -> None:
    """严格结构化响应不可解析时，只降级到JSON Mode重试一次。"""
    provider = KimiProvider(
        api_key="test-key",
        base_url="https://example.invalid/v1",
        model="kimi-k3",
        timeout_seconds=1,
    )
    fake = FakeRunnable(["not-json", '{"value":"120.50"}'])
    provider._client = fake

    result = provider.generate_structured(
        system_prompt="test",
        user_content=[{"type": "text", "text": "test"}],
        schema_name="test_schema",
        json_schema={"type": "object", "required": ["value"]},
        reasoning_effort="low",
    )

    assert result == {"value": "120.50"}
    assert fake.invoke_count == 2
    assert fake.bindings[0]["response_format"]["type"] == "json_schema"
    assert fake.bindings[1]["response_format"] == {"type": "json_object"}


def test_kimi_provider_accepts_single_json_markdown_fence_without_retry() -> None:
    """整段JSON被单层代码围栏包裹时可安全解包，不截取任意解释文本。"""
    provider = KimiProvider(
        api_key="test-key",
        base_url="https://example.invalid/v1",
        model="kimi-k3",
        timeout_seconds=1,
    )
    fake = FakeRunnable('```json\n{"value":"120.50"}\n```')
    provider._client = fake

    result = provider.generate_structured(
        system_prompt="test",
        user_content=[{"type": "text", "text": "test"}],
        schema_name="test_schema",
        json_schema={"type": "object"},
        reasoning_effort="low",
    )

    assert result == {"value": "120.50"}
    assert fake.invoke_count == 1


def test_metric_numeric_fields_normalize_unambiguous_units() -> None:
    """模型偶尔重复单位时先规范化，持久化结果仍保持纯十进制。"""
    metric = ExtractedMetric(
        metric_name="本期电费",
        actual_value="1,220.50元",
        benchmark_lower_value=None,
        benchmark_upper_value="-",
        reported_over_limit_rate_percent="20.5%",
        unit="元",
        comparison_type=None,
        comparison_applicability="unknown",
        applicability_reason=None,
        is_over_limit=None,
        evidence_text=None,
        evidence_element_ids=[],
        confidence=0.8,
    )

    assert metric.actual_value == "1220.50"
    assert metric.reported_over_limit_rate_percent == "20.5"
    assert metric.benchmark_upper_value is None


def test_metric_numeric_fields_turn_ambiguous_expressions_into_null() -> None:
    """可选数值遇到公式或区间时归一为空，绝不静默截取其中一个数字。"""
    metric = ExtractedMetric(
        metric_name="本期电费",
        actual_value="120至150元",
        benchmark_lower_value=None,
        benchmark_upper_value=None,
        reported_over_limit_rate_percent=None,
        unit="元",
        comparison_type=None,
        comparison_applicability="unknown",
        applicability_reason=None,
        is_over_limit=None,
        evidence_text=None,
        evidence_element_ids=[],
        confidence=0.8,
    )

    assert metric.actual_value is None


def test_not_applicable_metric_requires_explicit_material_reason() -> None:
    """模型不能仅因截图显示短横线，就把指标擅自归类为业务不适用。"""
    with pytest.raises(ValueError, match="必须说明材料中的适用性原因"):
        ExtractedMetric(
            metric_name="历史日均电量标杆-环比",
            actual_value=None,
            benchmark_lower_value=None,
            benchmark_upper_value=None,
            reported_over_limit_rate_percent=None,
            unit="度",
            comparison_type="环比",
            comparison_applicability="not_applicable",
            applicability_reason=None,
            is_over_limit=None,
            evidence_text="截图显示-",
            evidence_element_ids=[],
            confidence=0.7,
        )
