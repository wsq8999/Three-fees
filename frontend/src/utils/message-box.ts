import { ElMessageBox } from "element-plus";
import type { ElMessageBoxOptions } from "element-plus";

const STANDARD_MESSAGE_BOX_CLASS = "standard-message-box";
const STANDARD_MESSAGE_BOX_OVERLAY_CLASS = "standard-message-box-overlay";

export function standardConfirm(
  message: string,
  title: string,
  options: ElMessageBoxOptions = {},
): ReturnType<typeof ElMessageBox.confirm> {
  return ElMessageBox.confirm(message, title, {
    ...options,
    appendTo: document.body,
    customClass: [STANDARD_MESSAGE_BOX_CLASS, options.customClass]
      .filter(Boolean)
      .join(" "),
    modalClass: [STANDARD_MESSAGE_BOX_OVERLAY_CLASS, options.modalClass]
      .filter(Boolean)
      .join(" "),
  });
}
