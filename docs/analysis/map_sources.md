# P3R planner: Reload-specific sources, rules and input/deviation UX

## 0. Summary

1. **Best source overall: the aqiu384 megaten-database data extracted from the P3R game files, not any wiki.** The GitHub repo `aqiu384/megaten-database` holds tables pulled from P3R's own files under `src/p3r/Content/Xrd777/...`. `Xrd777` is the main P3R game. `Astrea` is the Episode Aigis DLC and must not be mixed in. The repo was first loaded on 2024-03-04 (commit "p3r init game data"). Those tables give the Social Link point thresholds per rank, the bonus multipliers, the social-stat thresholds, the Sunday/holiday invitation dates, film-festival hangouts, gifts, Tartarus block opening dates, and the official Simplified Chinese (zh-Hans) names for arcana, characters and Social Link labels. This is a third-party extraction, not an ATLUS publication. It is still the strongest evidence short of checking in the game, and it is stronger than anything in the current rule pack.
2. **The day-by-day calendar should stay on the aqiu calendar page.** The repo already pins `https://aqiu384.github.io/megaten-database/p3r/social-events`. I re-downloaded it: SHA256 `404dea95…3e4ec8`, 190,914 bytes, identical to `rules/p3r/calendar_full_candidate.json` → `sources[aqiu_calendar]`. Its git history shows the last edit on 2024-07-15. Cross-check it against the atwiki Reload pages (`w.atwiki.jp/wiki10_persona3/pages/420|453|455.html`) and game8.jp `コミュ一覧`.
3. **Official zh-Hans terms: use the game-file string tables plus the Steam Simplified Chinese achievement text.** Both are ATLUS's own strings. Three terms in the task prompt are not the official ones: 女帝, 倒吊人 and 月. Official SC is **女皇, 倒悬者, 月亮**. The app already uses the correct three, but it has other name mismatches (§6).
4. **Sites that blocked automated fetch, so nothing was taken from them live:**
   - megatenwiki.com shows a Cloudflare "Verify you are human" check. I did not bypass it.
   - w.atwiki.jp, gamefaqs, altema.jp, megamitensei.fandom.com and persona.huijiwiki returned 403.
   - wiki.biligame.com was unusable.
   - Repo files that cite atwiki or GameFAQs rely on the earlier session's hashed reads.

## 1. Best source by data category

| Category | Primary (Reload-specific) | Cross-check | Do not use / caution |
|---|---|---|---|
| SL weekdays, slots, locations | aqiu social-events page, "Availability" table and day/night calendars (already in repo) | game8.jp `p3re/714334` (fetched OK); kamigame `p3r/page/299048913220936325.html` (fetched OK); atwiki 455/453 (repo snapshot only) | game8.co EN overview `archives/435602` has outliers: Hermit "every day", Aeon M/W/F/Sa, Star +Tue, Hierophant without Sun, Emperor "daily after January". Inverse, pushsquare and "calendar" spam sites are unreliable. appmedia said at launch that its data was based on P3P. |
| SL unlock conditions | aqiu per-arcana sections plus game8.jp and kamigame | game8.co per-link pages (439582 Mitsuru, 439756 Mutatsu), rpgsite, siliconera | The conflicts in §2 stay open; use the strictest claim. |
| Affinity points and thresholds | Game-file `Community/Event/CommunityEventDataAsset.tsv`, `Community/Coefficient/General/DT_GeneralCoefficient.tsv`, `Community/Present/CommunityPresentDataAsset.tsv` | aqiu answer table (+5 per ♪); megatenwiki Social_Link Reload section, seen only as a search snippet, which cites the *P3R Official Complete Guide* p54 | No source gives the raw points for an ordinary hangout (see §3). |
| Social-stat thresholds and level names | Game-file `Community/Parameter/HeroParameterDataAsset.tsv`, `DT_Hero_Parameter_Name.tsv` | Prima Games (same numbers) | — |
| Stat activity gains | aqiu "Social Stat Regular Activities" (hand-compiled, not extracted from game files) | GameFAQs TenSquare3 81159 v2.5.0 and atwiki 469 (both in repo `stats_actions.json`) | kamigame `人間パラメータ` shows ♪-style +1..+3 values and weekdays that disagree with aqiu. The game's ♪ display for stats is coarse (Steam thread: ¥3000 arcade and ¥1000 burger both show 2 notes). |
| Calendar (locks, exams, vacations, invitations) | aqiu calendar; game-file `Community/Holiday/Schedule/HolidayScheduleDataAsset.tsv`, `SeasonEvent/Movies/MoviesDataAsset.tsv` | atwiki 420 route (repo) | — |
| Tartarus blocks | Game-file `Xrd777/Activity/ActivityDataAsset.tsv`; aqiu `docs/p3r/tartarus.md` | repo `tower_stages.json` | Ignore the `Astrea/Activity/...` file (DLC). |
| Missing-person deadlines | aqiu `docs/p3r/overworld.md` "Missing People" | game8.jp `p3re/721428` | — |
| zh-Hans localization | Game-file `Blueprints/common/Names/DatRaceDataAsset.tsv` (arcana), `DatPlayerNameDataAsset.tsv` / `DatPlayerFirstNameDataAsset.tsv` / `DatPlayerLastNameDataAsset.tsv`, `DatSocialLinkDataAsset.tsv`; Steam SC achievements `steamcommunity.com/stats/2161700/achievements/?l=schinese` | PlayStation zh-hans page; 3DM guide `3dmgame.com/gl/3928716.html` table of contents | The official SC site `asia.sega.com/p3r/cn/` renders its text as SVG images, so it can't be read by machine. |

Raw file URL pattern: `https://raw.githubusercontent.com/aqiu384/megaten-database/master/src/p3r/Content/Xrd777/<path>`. All files listed above downloaded with HTTP 200.

## 2. Reload Social Link availability table

ArcanaID in the game files = arcana number + 1, with Aeon = 22. AS = after school, D = daytime (holidays and Sundays), E = evening. "Consensus" means aqiu plus at least two of game8.jp, kamigame and atwiki agree. Names are the official zh-Hans game-file names.

| # | Arcana | Partner (game SL label) | Unlock / earliest | Days (consensus) | Slot | Location | Conflicts / notes |
|---|---|---|---|---|---|---|---|
| 0 | 愚者 | 特别课外活动部 | Automatic | Story ranks: R1 4/18, R2 4/20, R3 5/9, R4 7/7, R5 7/22, R6 11/2, R7 11/4, R8 skipped, R9 11/28, R10 12/31 | — | — | game8.jp/kamigame give start 4/20 |
| 1 | 魔术师 | 友近 健二 (同班同学) | 4/22 automatic | Tue Thu Fri | AS | 2F classroom | — |
| 2 | 女教皇 | 山岸 风花 | ≥6/19; Fortune R1 + Courage Lv6 | Mon Fri Sat | AS | 2F hallway | Inverse lists M/W/Th/Sa; reject |
| 3 | 女皇 | 桐条 美鹤 | ≥11/21; Academics Lv6. aqiu alone adds "1st place in one exam" | **Tue Thu Sat** (all sources); **Wed** (aqiu, atwiki, game8.jp, game8 EN); Mon/Fri only in aqiu's December cells, altema and game8 EN | AS | 1F faculty-office hallway | Every school day of the week before exams (12/7–12/12; aqiu cells, rpgsite/siliconera). In aqiu's January cells she appears only Tue/Wed/Thu/Sat. **Plan on Tue/Wed/Thu/Sat plus the pre-exam week.** Treat Mon/Fri as unconfirmed and don't count gains from them. |
| 4 | 皇帝 | 小田桐 秀利 (学生会) | ≥4/27; join student council | Mon Wed Fri | AS | Student council room, 2F | Unavailable 11/3–11/29 (aqiu); stops before exams (kamigame) |
| 5 | 教皇 | 北村 文吉 / 北村 光子 (旧书店老夫妻) | ≥4/25; persimmon leaf | Tue–Sun | D/AS | Bookworms, 严户台商店街 | JP sites add 祝 (holidays); aqiu cells show Monday holidays as "-". Lost unless Bunkichi is rescued (missing 9/12, deadline 10/3). |
| 6 | 恋爱 | 岳羽 由加莉 | ≥7/25; Charm Lv6 | Mon Wed Thu Sat | AS | 2F classroom | — |
| 7 | 战车 | 宫本 一志 (体育社团) | ≥4/23; join a sports club | Mon Tue Thu Fri | AS | 2F classroom (JP) / track (aqiu) | — |
| 8 | 正义 | 伏见 千寻 (学生会会计) | R1 ≥5/7; Emperor R1 + three intro talks | Tue Thu Sat | AS | 2F hallway | — |
| 9 | 隐者 | Ｙ子 (互联网) | ≥4/29; online game on room PC | Sun + holidays (aqiu cells: 4/29, 5/4, 5/5, 9/21–23, **10/12**, 11/23, 12/23, 1/11) | D | Own room | aqiu's note text omits 10/12 but its calendar cell has it. game8 EN says "every day"; reject. |
| 10 | 命运 | 平贺 庆介 (文艺社团) | ≥6/17; join art club | Tue Wed Thu | AS | 1F art room | — |
| 11 | 力量 | 西胁 结子 (社团女经理) | Chariot R2 + walk-home intro; earliest 4/24–4/27 depending on source | Wed Sat | AS | 2F hallway | — |
| 12 | 倒悬者 | 舞子 (神社的小女孩) | Intro ≥5/6 (Weird Takoyaki + Mad Bull); R1 5/6–5/11 depending on source | Mon Wed Sat | D/AS | Naganaki shrine (长鸣神社) playground | JP sites add 祝; aqiu doesn't. Lost unless Maiko is rescued (10/21–11/2). |
| 13 | 死神 | 法洛斯 (神秘少年) | 6/12 automatic | R1 6/12, R3 7/12, R5 8/7, R6 9/12, R8 10/6, R10 11/4 | — | — | — |
| 14 | 节制 | **贝培** (同好会的留学生) | Hierophant R3 + Academics Lv2; earliest 5/6–5/8 | Tue Wed Fri | AS | 1F home-ec room | game8 EN says 5/26 or 5/29; reject |
| 15 | 恶魔 | 田中社长 (电视购物社长) | Charm Lv4 + Hermit **R3 (aqiu) vs R4 (game8.jp, kamigame, altema, game8 EN)**; pay ¥20k + ¥10k + ¥10k on separate nights | Tue Sat | E | Paulownia Mall | Use Hermit R4. aqiu cells don't add holidays. |
| 16 | 塔 | 无达 (与众不同的僧侣) | Strength R4 + monk rumour from Yuko + drink sequence. Courage requirement **2 (kamigame, dotesports) / 3 (game8.jp) / 4 (aqiu, game8 EN, rpgsite, samurai-gamers)** | Thu Fri Sat Sun | E | Club Escapade | Use Courage Lv4 unless the user says the link is already unlocked |
| 17 | 星 | 早濑 护 (外校的王牌选手) | ≥8/5; Courage Lv4 | Wed Fri Sun | D | 严户台商店街 1F | kamigame adds Sat; game8 EN adds Tue |
| 18 | 月亮 | 末光 望美 (美食大王) | Magician R3 rumour + Charm Lv2 + quiz + Odd Morsel; earliest 4/28 (aqiu cell) or 5/8 (JP) | Every day | D | Paulownia Mall fountain | kamigame leaves out Thu |
| 19 | 太阳 | 神木 秋成 (命不久矣的青年) | Hanged R3 + Academics Lv4 + red fountain pen via Koromaru; earliest **8/9** | Sun | D | Naganaki shrine | aqiu's table says 8/23, but its own calendar has 8/9; game8 body says 9/6 |
| 20 | 审判 | 倪克斯讨伐队 | 12/31 automatic | R2 at Adamah 227F; R3–R10 on clearing 230/236/241/246/247/253/254/255F | — | Tartarus | — |
| — | 永劫 | 埃癸斯 | ≥1/8 | Mon–Sat | AS | 2F classroom | game8 EN says M/W/F/Sa; reject |

**Rules that apply across the table (aqiu):**
- No school link is available during vacations.
- Every school link except Empress is off the week before exams.
- Holiday availability: use the per-date cells, not weekday rules plus a "祝" claim.

## 3. Affinity mechanics in Reload

**Points needed for each rank, from `CommunityEventDataAsset.tsv` (game files).** Column `RankN` is the points needed to go from N−1 to N. I matched it against aqiu's "X/Y points to next rank" rows: Magician R3 needs 15 to reach R4 (`Rank4` column), Magician R9→R10 needs 22.

| Link | →R4 | →R5 | →R6 | →R7 | →R8 | →R9 | →R10 |
|---|---|---|---|---|---|---|---|
| Magician | 15 | 15 | 15 | 15 | 22 | 22 | 22 |
| Priestess | 15 | 22 | 30 | 30 | 35 | 40 | 55 |
| Empress | 22 | 22 | 22 | 22 | 22 | 22 | 22 |
| Emperor | 0 | 15 | 15 | 15 | 15 | 22 | 22 |
| Hierophant | 20 | 20 | 20 | 20 | 20 | 20 | 30 |
| Lovers | 15 | 22 | 22 | 35 | 35 | 40 | 55 |
| Chariot | 15 | 15 | 15 | 15 | 22 | 22 | 22 |
| Justice | 22 | 22 | 22 | 30 | 30 | 30 | 40 |
| Hermit | 20 | 20 | 20 | 20 | 20 | 20 | 20 |
| Fortune | 15 | 15 | 15 | 15 | 15 | 22 | 22 |
| Strength | 15 | 15 | 22 | 22 | 35 | 40 | 40 |
| Hanged | 20 | 20 | 20 | 30 | 15 | 30 | 30 |
| Temperance | 15 | 15 | 15 | 15 | 10 | 22 | 22 |
| Tower | 25 | 25 | 25 | 30 | 30 | 40 | 40 |
| Star | 20 | 20 | 20 | 20 | 30 | 30 | 30 |
| Moon | 15 | 15 | 22 | 22 | 22 | 22 | 15 |
| Aeon | 0 | 0 | 0 | 0 | 0 | 0 | 30 |

Every rank up to R3 needs 0 points. Fool, Death, Devil, Sun and Judgement need 0 at every rank: they are story-driven, paid (Devil) or rank up on every meeting. **Planner consequence:** Devil (after paying), Sun, Aeon R1–R9, Emperor R3→R4, and every link's R1→R3 are rank-ups on every legal meeting. These can be scheduled without any affinity estimate.

**Bonus multipliers, from `DT_GeneralCoefficient.tsv`:**
- `CMM_POINTBONUS_ARCANA` 1.51 (matching-arcana Persona in stock)
- `CMM_POINTBONUS_CHARM` 1.51 (Charm Lv6)
- `EVT_EXAM_RESULT_TOP` 1.51
- `EVT_EXAM_RESULT_GOOD` 1.21

aqiu says the two exam bonuses are exclusive and everything else multiplies together. This is the game-code evidence that `rules/p3r/affinity_model_evidence.json` → `conflicts[charm_uncertainty_older_guide]` says is missing. Rounding and when the exam bonus expires are still unknown.

**Answer points.** All 193 choice rows on the aqiu page show **+5 per ♪**: 92 at +15/3♪, 95 at +5/1♪, 3 at +10/2♪, 3 at +0/0♪.

**Ordinary hangout.** atwiki 455 (repo locator 320–324) says the game shows ♪♪ for a normal hangout. At 5 points per ♪, that implies about +10 base points. This is still indirect: no game table contains the raw value, and it isn't known whether the displayed notes include multipliers. Keep +10 as a labelled estimate, per the handoff.

**Other point sources:**
- **Gifts** (`CommunityPresentDataAsset.tsv`): +10 or +50 per arcana, nine giftable links.
- **Film festival** (`MoviesDataAsset.tsv`): +10 SL and +4 stat. 8/17–8/23 by phone invite, 8/24–9/23 in person. Each date has a rank window, e.g. Temperance on 8/18 needs R2–9 and Justice on 8/23 needs R2–10.
- **Koromaru walks**: +15 for links that aren't party members.
- **Dorm hangouts with Yukari, Mitsuru, Fuuka or Aigis**: +1, or +2 with both the top-of-class and Charm-6 flags (aqiu). An atwiki comment says a fixed +2 instead. This conflict is unresolved; never apply the ordinary-hangout model to dorm hangouts.
- **Shrine fortunes and the Club Escapade "dating site" notes** add SL points (Steam community; amount unverified).

**Sunday/holiday invitations, `HolidayScheduleDataAsset.tsv`** (`Month Day ArcanaID1..3`), 34 dates: 4/26 Ma; 5/4 Ch; 5/5 Ma; 5/24 Ma,Ch; 5/31 Ju,St; 6/7 Te,Ch; 6/14 Ch,Ma; 6/21 Ju,Te; 6/28 Pr; 7/5 St,Ju; 7/26 Te,Ju; **8/3 Lo**; 8/4 Pr,Fo,St; 8/5 Ma,Ch; 8/7 Fo,Te; **8/9 Ju,Lo**; 8/26 Ma,St; 8/27 Te,Ju; 9/13 Lo,Pr; 9/22 Ma,St; 9/23 Lo,Fo; 9/27 Te,Ch,Ju; 10/18 Ma,Fo,Ch; 10/25 St,Ju,Lo; 11/8 Te,Ma,Ch; 11/15 Pr,Lo,St; 11/29 Ju,Te; 1/4 Em,Ma,Fo; 1/5 Te,Pr; 1/6 Ch,St,Ju; 1/7 Lo,Em; 1/10 Fo,Ch,Lo; 1/11 Ma,Ju,Fo; 1/17 Pr,Lo,St; 1/24 Em,Lo,Pr.

This settles `calendar_full_candidate.json` → `sourceInternalInvitationDiscrepancies` (Lovers on 08-03 and 08-09): **the game data includes them.** They still require Lovers to be unlocked.

## 4. Social stats

**Thresholds, from `HeroParameterDataAsset.tsv` (per-level increments).**

| Stat | Increments | Cumulative points for Lv2–Lv6 |
|---|---|---|
| Academics | 20, 35, 45, 55, 75 | 20 / 55 / 100 / 155 / 230 |
| Charm | 15, 15, 15, 25, 30 | 15 / 30 / 45 / 70 / 100 |
| Courage | 15, 15, 15, 15, 20 | 15 / 30 / 45 / 60 / 80 |

These match `rules/p3r/stats_actions.json` → `statRules` and Prima Games.

**JP level names (`DT_Hero_Parameter_Name.tsv`, Lv1–Lv6).** I found no official zh-Hans level names, so show `Lv.N` until checked in the game.
- 学力: もう少し / 出来なくはない / そこそこ良い / なかなか優秀 / かなりの秀才 / 天才
- 魅力: これといって / 磨けば光る / そこそこある / 光っている / オーラが出ている / カリスマ
- 勇気: 今ひとつ / ないこともない / ここぞでは違う / 頼りがいがある / 肝が据わっている / 漢

**Activities (aqiu raw values; days listed Sun..Sat as B both, D day, E evening):**
- Academics:
  - Library +4, school days only.
  - Desk study +2.
  - Shrine donation +2 / +3 / +4 for ¥100 / ¥1,000 / ¥10,000.
  - Wakatsu Prodigy Platter +3, ¥680, not Tue.
  - Wakatsu Seafood Full Course +4, ¥900, evenings Sun/Mon/Thu/Fri. Needs Charm Lv3 and having eaten the Prodigy Platter on a Seafood night.
  - Arcade "You're the Answer" +4, ¥3,000, Wed/Sat.
  - Screen Shot film +4, ¥1,500, Wed/Sat daytime.
- Charm:
  - Chagall Pheromone Coffee +2, ¥500.
  - Hagakure Pork Ramen +3, ¥900, Mon–Fri.
  - Hagakure Bowl +4, ¥1,200, evenings Tue/Wed/Fri. Needs Courage Lv3 and having eaten Pork Ramen on a Bowl night.
  - Arcade "High School of Youth" +4, Mon/Thu.
  - Screen Shot film +4, Tue/Fri.
- Courage:
  - Karaoke +2, ¥800, Mon–Sat.
  - Wilduck Mystery Burger +3, ¥1,000, not Thu.
  - Weekend Wilduck Set +4, ¥1,200, Sat/Sun evenings. Needs Request #11 (Big Eater Challenge).
  - Arcade "House of the Deceased" +4, ¥3,000, Tue/Fri.
  - Screen Shot film +4, Mon/Thu.
- Part-time jobs (pay confirmed by game-file `Help/BMD_ArbeitHelp.tsv`):
  - Chagall, evening: ¥2,500+ ("pays more on Tue"), Charm +1, Courage +1.
  - Be Blue V, day, from 7/24: ¥3,500+, Academics +1, Courage +1.
  - Screen Shot, Sunday day (and Saturday when no school): ¥5,000+, Academics +1, Charm +1.
- Free gains:
  - Class answers: Charm +2.
  - Top of the class in an exam: Charm +4.
  - In class, stay awake for Academics +2 or sleep for Courage +2.
  - Nurse's office after a Tartarus night or full moon: Courage +2, takes no time.
  - Summer school 8/10–8/15: Academics +18 total.
  - Request #75: Academics +16 over 8 days.

The library conflict (+4 aqiu vs +2 atwiki 469 and GameFAQs 81159) is still open. Keep it excluded or use +2 as the lower bound.

**Exam top-of-class gates** (aqiu "Required For"): Academics Lv3 by the 5/25 results, Lv4 by 7/24, Lv5 by 10/19, Lv6 by 12/21, plus correct answers. The result decides whether the 1.51 or 1.21 multiplier is active.

## 5. Reload calendar facts for the planner

- **Tartarus block openings (`Xrd777/Activity/ActivityDataAsset.tsv`, StartMonth/Day → floor range):** 4/21 ≤22F; 5/10 23–43; **6/13** 44–69; 7/9 70–92; 8/8 93–118; 9/10 119–144; 10/6 145–172; 11/6 173–198; **12/3** 199–226; 1/1 227–256; 1/31 257+. This settles `rules/p3r/tower_stages.json` stage_02/03 (`dateConflicts ["06-13","06-14"]`) and stage_08/09 (`["12-03","12-10"]`) in favour of **06-13** and **12-03**. Caveat: this is the table's start date, and story events can still occupy that evening.
- **Block names** (EN aqiu / SC from 3DM, a third-party guide built from the SC game): Thebel 世俗之庭台伯勒, Arqa 奇颜之庭阿尔卡, Yabbashah 鄙俗之庭亚巴扎, Tziah 奢豪之庭茨尔阿, Harabah 焦炎之庭哈拉瓦, Adamah 忧郁之庭亚达玛. Monad = 蒙纳多 (official Steam text "蒙纳多之门"); the Monad Door tutorial is at 80F.
- **Full moons:** 4/9, 5/9, 6/8, 7/7, 8/6, 9/5, 10/4, 11/3; finale 1/31. Matches the repo's `fullMoonStoryEvents`.
- **Exams:** about 5/18–5/22, 7/14–7/17, 10/13–10/16, 12/14–12/18. Results on 5/25, 7/24, 10/19 and 12/21.
- **Missing-person rescue windows** (aqiu overworld.md):
  - 6/18–7/6
  - 6/26–7/6
  - 8/3–8/5
  - 8/19 or 8/29–9/4
  - **Bunkichi 9/12–10/3** (Hierophant is lost if missed)
  - **Maiko 10/21–11/2** (Hanged Man is lost if missed)
  - 11/28–12/1
  - 12/22–12/30
  - 1/15–1/30

  These should drive "must enter Tartarus by …" constraints.
- **Link Episodes** (aqiu). The official EN name is "Link Episodes". They give stats or items, not SL points, and they take the time slot.
  - Junpei, after school: 5/12–7/3, 8/9–8/28, 11/7–11/11 (+3.5 white flower → 1/21 event takes the slot), 12/19–12/26, 1/15–1/29.
  - Akihiko, evening: 5/29–7/10, 7/24–8/31, 10/9–11/2, 12/12–12/22, 1/4–1/29.
  - Koromaru, Tue/Wed daytime: from 8/22.
  - Ken, Tue/Wed evening: 9/8–9/30, 11/10–12/2, 12/22–12/30, 1/19–1/27.
  - Shinjiro, daytime: 9/4–10/2.
  - Ryoji (11/9, 11/12–16, 11/18, 12/1, 12/31) and Takaya (8/14, 8/20, 9/18, 1/13–1/29) are fixed dates.
- **Dorm evening hangouts by usual weekday** (aqiu):
  - Mon: Junpei reading, Mitsuru tea, Koromaru DVD
  - Tue: Yukari DVD, Fuuka gardening
  - Wed: Akihiko cooking, Aigis reading (from 9/2)
  - Thu: Fuuka reading, Koromaru brushing
  - Fri: Yukari cooking, Ken DVD
  - Sat: Junpei gardening, Akihiko DVD
  - Sun: Mitsuru reading, Aigis gardening, Ken coffee

**Reload rules that differ from FES/P3P:**
- No female protagonist and no FES "The Answer" (Episode Aigis is separate paid DLC: the `Astrea` data).
- **No fatigue or sickness system**, so Tartarus nights carry no next-day condition penalty (siliconera, samurai-gamers).
- Link Episodes replace the male SEES Social Links.
- Dorm activities unlock Combat Characteristics.
- Theurgy (神通法) affects combat only.
- Reload's own stat thresholds, SL thresholds and multipliers are above. P3P/FES numbers must not be reused.
- Social Link names, schedules and choices: use Reload pages only. A Steam thread warns that FES/P3P rank-up timing does not carry over.

## 6. Official Simplified Chinese terms

**Arcana (`DatRaceDataAsset.tsv` zh-Hans):** 愚者, 魔术师, 女教皇, **女皇**, 皇帝, 教皇, 恋爱, 战车, 正义, 隐者, 命运, 力量, **倒悬者**, 死神, 节制, 恶魔, 塔, 星, **月亮**, 太阳, 审判, 永劫.

**Names (`DatPlayerNameDataAsset.tsv`):**

| Character | Official zh-Hans |
|---|---|
| Protagonist (default) | 结城 理 |
| SEES | 岳羽 由加莉, 伊织 顺平, 真田 明彦, 桐条 美鹤, 山岸 风花, 埃癸斯, 天田 乾, **虎狼丸** (Koromaru), 荒垣 真次郎 |
| School links | 友近 健二, 小田桐 秀利, 宫本 一志, 西胁 结子, 平贺 庆介, 伏见 千寻 |
| Hierophant | 北村 文吉, 北村 光子 |
| Other links | Ｙ子 (full-width), 舞子 (no surname in the game table), 法洛斯 (Pharos), **贝培** (Bebe), 田中社长, 无达, 早濑 护, 末光 望美, 神木 秋成 |
| Others | **伊莉莎白** (Elizabeth), 隆哉, 壬, 千鸟, 望月 绫时 |

**Steam SC achievements (ATLUS text):**
- Social Link = **社群**; rank = **级别** ("将任一社群的级别提升至了ＭＡＸ")
- Social stats = **个人能力值** ("将任一项个人能力值提升至了ＭＡＸ")
- 塔尔塔罗斯, 蒙纳多之门, 黑暗领域, 神通法, 黄昏的碎片, 洗牌时间, 大阿尔卡那, 总攻击, 合体, 打工, 失踪者, 恋人, 宿舍, **严户台**商店街, 不羁之力, 猎杀者, 伊莉莎白, 休息日的出游邀请短信

PlayStation zh-hans page: 影时间, 特别课外活动部, 阴影, 人格面具, 天鹅绒房间, 港湾人工岛, 埃癸斯. Steam store: 月光馆学园.

**Mismatches in the shipped app (counted in assets):**

| Shipped | Official | Where (count) |
|---|---|---|
| 贝贝 | 贝培 | `data.json` ×9, `display_metadata.json` ×18, `planner_v2/social_goal_identity_labels.json` ×2 |
| 伊丽莎白 | 伊莉莎白 | `data.json` ×22, `display_metadata.json` ×44 |
| Y子 | Ｙ子 | Full-width in the game; cosmetic |
| 大桥舞子 | 舞子 | Hanged Man label in `social_goal_identity_labels.json` |
| 伙伴们 | 倪克斯讨伐队 | Judgement label; the change was made deliberately to avoid spoilers. Keep it, but record the official term. |
| "可见社交属性" | 个人能力值 | `v2/PlayerStateForm.java:38,140` |
| "社群等级" | 社群级别 | Form labels (`PlayerStateForm.java:51,93`, `GoalPlannerHomeActivity.java:44,52`) |

The original CN guide's use of 社群, 塔尔塔罗斯, 神通法, 蒙纳多, 严户台, 虎狼丸, 女皇, 倒悬者 and 月亮 matches the official strings, which suggests it was written from the SC game. It calls Link Episodes "羁绊剧场"; that is unverified but plausible.

## 7. Making input easy and handling deviations

Grounded in what Reload actually shows the player.

**What the player can see in the game:**
- date and time slot, cash
- each stat's level (1–6)
- each SL's rank (0–10)
- the ♪ count after each answer (+5 points per ♪ for SL answers)
- highest Tartarus floor reached
- exam placing (1st / top ten / other)
- whether a matching-arcana Persona is in stock
- probably a rank-up cue. A Steam user reports a "rank up" marker in the map's city details, and a pre-hangout line like "doesn't seem like I can rank up". This is unconfirmed; the owner can check it in the game in a minute.

The app already asks "游戏是否明确提示下次可升级" (`PlayerStateForm.java:52`, `GoalPlannerHomeActivity.java:44`), and the home screen says "先看游戏提示" (`GoalPlannerHomeActivity.java:78`). **Make this rank-up cue the main per-link input.** A "yes" makes the next meeting a guaranteed rank-up with no hidden-point maths.

**Input design:**
1. **Pre-fill everything.** Show forecast values in a distinct style labelled "预测". Add "全部与游戏一致" per section, which turns them into observations in one tap. Only fields the user changes are edited.
2. **Replace the 22 expand/collapse panels** (`PlayerStateForm.java:50`) and the free-text rank field (`:51`) with one row per link: `arcana · name` | rank chips 0–10 / "?" | rank-up cue 是/否/没看 | goal star (☆/★/★★/★★★). Default goal rank is 10. Show romance-branch fields (`:53–54`) only at rank ≥8 for the six romanceable links.
3. **Stats as segmented `Lv.1–6` controls.** Optionally add "今天刚升到这一级", which narrows the hidden-point range to [threshold, threshold + that action's gain).
4. **Bonus state as two one-time questions**, not per action:
   - "通常带同阿尔卡那人格面具?", default 否, which is the conservative choice
   - "上次考试名次" (第一 / 前十 / 其他), asked automatically on each results date
5. **Answer outcomes as a total ♪ count.** On each SL result sheet, offer an optional "这次一共看到几个♪" (0–9). It maps to points at 5 per ♪ without depending on option order, so it avoids the problem the handoff raises about foreign option numbers not matching Chinese option order. The default is "按推荐回答".

**Deviation handling:**
1. **Done on the widget accepts the predicted result.** Tapping the task opens a sheet for that action only:
   - SL: 已升到N / 没升级 / 没去
   - Tartarus: one cash mode (net change or balance, never both), highest floor, correction without spending another visit (already there, `GoalPlannerHomeActivity.java:41–50`)
   - Training: one stat with its observed level

   "没去" offers that slot's two or three most likely alternatives (another link that is available then, a stat activity, Tartarus, rest) plus "不记得". "不记得" uses up the slot and credits nothing.
2. **Coming back after several days:** show "上次记录到 5/12，现在是?" and offer two paths.
   - (a) List the planned actions in between, all ticked; the user unticks what they didn't do.
   - (b) Recommended when more than about three days have passed: a checkpoint screen with forecast ranks and stats pre-filled, where the user edits only the differences.

   A state checkpoint is exact for ranks and much cheaper than rebuilding the history.
3. **Invitations:** on the 34 game-data invitation dates, the widget asks "今天收到谁的邀约?" and lists that date's one to three candidates. An accepted invitation overrides the weekday rule.
4. **Replanning:** replan automatically after every observation. Bias toward a stable plan by keeping the next two or three days unchanged unless they become infeasible. Show "计划已调整：N 项" with the changes. Never write forecasts into observed state. Corrections replace earlier events by ID so nothing is counted twice; the tower flow already does this.

## 8. Action items for the lead engineer

1. Add the game-file tables as a new source class: `CommunityEventDataAsset`, `DT_GeneralCoefficient`, `HeroParameterDataAsset`, `HolidayScheduleDataAsset`, `MoviesDataAsset`, `CommunityPresentDataAsset`, `ActivityDataAsset`, and the zh-Hans name tables. Pin raw URLs and SHA256 hashes. Upgrade the multiplier and threshold evidence from "community agreement" to "game-data extraction".
2. Use these to settle the tower_stages conflicts (06-13, 12-03) and the Lovers 8/3 and 8/9 invitations.
3. Treat every 0-point rank step as a deterministic rank-up: Devil after paying, Sun, Aeon R1–R9, Emperor R3→R4, and all links' R1→R3.
4. For contested unlock gates, plan with the strictest claim (Devil: Hermit R4; Tower: Courage Lv4; Empress: ask once about an exam 1st place), and let an observed unlock override it. For Empress weekdays, use Tue/Wed/Thu/Sat plus the 12/7–12/12 pre-exam week.
5. Fix the localization strings: 贝培, 伊莉莎白, Ｙ子, 舞子 label, 个人能力值, 级别.
6. Ask the owner to confirm two things in the game: the rank-up cue on the map or before a hangout, and the SC labels for stat levels and time slots.

**Scratch evidence:** downloaded page and table copies are in `/private/tmp/claude-501/-Users-xihehan-P3R-Widget/c55d2fa4-7208-4e25-be91-b3e985a53a3e/scratchpad/` (`dl_aqiu/`, `dl_gh/*.tsv`, `dl_steam/ach_*.txt`, `aqiu_day.json`). No project files were modified.