package app.p3r.dayplanner.v3.service;

/** Why a command was refused: a {@link PlannerApi.Receipt} error code plus Chinese text. */
final class Rejection {
    final String code;
    final String zh;
    /** For {@link PlannerApi.Receipt#CODE_GAP}: unrecorded open slots before the requested one. */
    final int missingSlots;

    private Rejection(String code, String zh, int missingSlots) {
        this.code = code;
        this.zh = zh;
        this.missingSlots = missingSlots;
    }

    static Rejection invalid(String zh) {
        return new Rejection(PlannerApi.Receipt.CODE_INVALID, zh, 0);
    }

    static Rejection gap(int missingSlots) {
        String zh = "前面还有 " + missingSlots + " 个时间没记，先补上？";
        return new Rejection(PlannerApi.Receipt.CODE_GAP, zh, missingSlots);
    }

    PlannerApi.Receipt toReceipt() {
        PlannerApi.Receipt r = new PlannerApi.Receipt();
        r.ok = false;
        r.errorCode = code;
        r.errorZh = zh;
        r.missingSlots = missingSlots;
        return r;
    }
}
