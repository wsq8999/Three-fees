import { expect, test } from "@playwright/test";

async function loginAs(
  page: import("@playwright/test").Page,
  username: string,
) {
  await page.goto("/login");
  await page.getByTestId("username-input").fill(username);
  await page.getByTestId("password-input").fill("mock-only-password");
  await page.getByRole("button", { name: "登录" }).click();
}

test("administrator can restore a session, navigate, and log out", async ({
  page,
}, testInfo) => {
  await loginAs(page, "admin");
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(
    page.getByRole("heading", { name: "待生成报告任务" }),
  ).toBeVisible();

  await page.reload();
  await expect(page).toHaveURL(/\/dashboard$/);
  await expect(page.getByText("系统管理员", { exact: true })).toBeVisible();
  await page.screenshot({
    path: testInfo.outputPath("dashboard-1366.png"),
    fullPage: false,
  });

  await page.getByRole("menuitem", { name: "用户管理" }).click();
  await expect(page).toHaveURL(/\/users$/);
  await expect(page.getByRole("button", { name: "新增用户" })).toBeVisible();
  await expect(page.getByText("南京市用户")).toBeVisible();

  const overflow = await page.evaluate(() => ({
    viewport: document.documentElement.clientWidth,
    content: document.documentElement.scrollWidth,
  }));
  expect(overflow.content).toBeLessThanOrEqual(overflow.viewport);

  await page.getByRole("button", { name: "打开用户菜单" }).click();
  await page.getByRole("menuitem", { name: "退出登录" }).click();
  await expect(page).toHaveURL(/\/login$/);
});

test("city user is denied access to user management", async ({ page }) => {
  await loginAs(page, "nanjing_user");
  await expect(page.getByRole("menuitem", { name: "用户管理" })).toHaveCount(0);

  await page.goto("/users");

  await expect(page).toHaveURL(/\/forbidden$/);
  await expect(
    page.getByRole("main").getByText("无权访问", { exact: true }),
  ).toBeVisible();
});

test("invalid credentials show a generic error", async ({ page }) => {
  await loginAs(page, "unknown");

  await expect(page).toHaveURL(/\/login$/);
  await expect(
    page.getByRole("alert").filter({ hasText: "账号或口令不正确" }),
  ).toBeVisible();
});

test("report draft image analysis appears on demand and generates Word", async ({
  page,
}) => {
  await loginAs(page, "admin");

  await page.goto("/reports/generate");
  await expect(page).toHaveURL(/\/reports\/generate$/);
  await page.getByRole("button", { name: "生成报告" }).first().click();

  await expect(page).toHaveURL(/\/reports\/drafts\/draft-1(?:\?|$)/);
  await expect(page.getByRole("heading", { name: "AI 报告助手" })).toHaveCount(
    0,
  );

  const fileChooserPromise = page.waitForEvent("filechooser");
  await page.getByRole("button", { name: "分析图片" }).click();
  await expect(
    page.getByRole("heading", { name: "AI 报告助手" }),
  ).toBeVisible();
  const fileChooser = await fileChooserPromise;
  await fileChooser.setFiles({
    name: "现场凭证.png",
    mimeType: "image/png",
    buffer: Buffer.from(
      "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAFgwJ/lm2INwAAAABJRU5ErkJggg==",
      "base64",
    ),
  });

  await expect(
    page.getByRole("heading", { name: "四、现场图片分析" }),
  ).toBeVisible();
  await expect(page.getByText("图片分析已创建版本").first()).toBeVisible();

  const downloadPromise = page.waitForEvent("download");
  await page
    .getByRole("button", { name: "人工确认，生成报告并下载 Word" })
    .click();
  await page.getByRole("button", { name: "生成并进入报告" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/^BG-202606-\d{6}\.docx$/);

  await expect(page).toHaveURL(/\/reports\/report-\d+(?:\?|$)/);
  await expect(page.getByText("现场凭证.png")).toBeVisible();
});
