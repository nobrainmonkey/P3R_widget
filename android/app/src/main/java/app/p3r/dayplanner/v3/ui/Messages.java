package app.p3r.dayplanner.v3.ui;

import app.p3r.dayplanner.v3.service.PlannerApi;

/** User-facing Chinese messages for save results; never shows raw exception text. */
final class Messages {
    static final String GENERIC_ERROR = "没能保存，请再试一次。";
    static final String STALE = "安排刚刚更新了，请再确认一次。";

    /** What to do on a 「暂时读不到记录」 screen, which offers 「重试」. */
    static final String LOAD_RETRY = "点「重试」再试一次，还不行就重新打开应用。";

    private Messages() {}

    /**
     * Body of a 「暂时读不到记录」 screen: the service's reason ("读取记录失败了"), without its own
     * instruction (the service's text also serves the widget, which has no 「重试」), then
     * {@link #LOAD_RETRY}, so the copy matches the button under it.
     */
    static String loadFailed(String errorZh) {
        String reason = errorZh == null || !containsCjk(errorZh) ? "" : errorZh.trim();
        reason = reason.replaceAll("[，,。；]?\\s*(请重新打开应用|请稍后再试|请再试一次)[。！]?$", "");
        if (reason.isEmpty()) {
            reason = "读取记录失败了";
        }
        return Zh.sentence(reason) + LOAD_RETRY;
    }

    /** Message for a rejected receipt (stale revision, invalid value, storage error). */
    static String receiptError(PlannerApi.Receipt receipt) {
        if (receipt == null) {
            return GENERIC_ERROR;
        }
        if ("stale".equals(receipt.errorCode)) {
            return STALE;
        }
        if (receipt.errorZh != null && !receipt.errorZh.isEmpty() && containsCjk(receipt.errorZh)) {
            return receipt.errorZh;
        }
        return GENERIC_ERROR;
    }

    static boolean isStale(PlannerApi.Receipt receipt) {
        return receipt != null && !receipt.ok && "stale".equals(receipt.errorCode);
    }

    /** True when the text contains Chinese, i.e. is fit to show (guards against raw English errors). */
    static boolean containsCjk(String text) {
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch >= 0x4E00 && ch <= 0x9FFF) {
                return true;
            }
        }
        return false;
    }
}
