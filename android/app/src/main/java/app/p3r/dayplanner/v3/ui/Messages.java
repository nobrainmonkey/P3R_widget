package app.p3r.dayplanner.v3.ui;

import app.p3r.dayplanner.v3.service.PlannerApi;

/** User-facing Chinese messages for save results; never shows raw exception text. */
final class Messages {
    static final String GENERIC_ERROR = "没能保存，请再试一次。";
    static final String STALE = "安排已更新，请按最新的一项再确认一次。";

    private Messages() {}

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
