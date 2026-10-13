package app.p3r.dayplanner.v2;
import org.junit.Test;import static org.junit.Assert.*;
public class GameDateTest {
 @Test public void fixedAxisMatchesAuditedJanuary(){assertEquals(552,GameDate.slot(1,2,false));assertEquals(553,GameDate.slot(1,2,true));assertEquals("1 月 2 日 白天",GameDate.label(552));}
 @Test public void yearBoundaryAndDayNight(){assertEquals(GameDate.slot(1,1,false),GameDate.slot(12,31,true)+1);assertEquals(40,GameDate.slot(4,21,false));}
 @Test(expected=java.time.DateTimeException.class) public void invalidDateRejected(){GameDate.slot(2,30,false);}
}
