package app.p3r.dayplanner.v2;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
/** Fixed game calendar axis, independent of device date/timezone. */
public final class GameDate {
    private static final LocalDate EPOCH=LocalDate.of(2009,4,1);
    private GameDate(){}
    public static int slot(int month,int day,boolean evening){
        if(month<1||month>12)throw new IllegalArgumentException("月份须为 1–12");
        LocalDate date=LocalDate.of(month>=4?2009:2010,month,day);
        return Math.toIntExact(ChronoUnit.DAYS.between(EPOCH,date))*2+(evening?1:0);
    }
    public static LocalDate date(int slot){if(slot<0)throw new IllegalArgumentException("Invalid game slot");return EPOCH.plusDays(slot/2);}
    public static String label(int slot){LocalDate d=date(slot);return d.getMonthValue()+" 月 "+d.getDayOfMonth()+" 日 "+(slot%2==0?"白天":"晚上");}
}
