import java.util.*;
import java.util.stream.*;

// ============= PROBLEM DESCRIPTION =============
/*
SUBSCRIPTION NOTIFICATION SCHEDULER

Platform: Interview Problem / Coding Assessment
Difficulty: MEDIUM

=============================================================================
PART 1: BASIC EMAIL SCHEDULING
=============================================================================

Problem:
Implement a notification system for subscription plans. Each user subscribes 
to a plan with a start date and duration. Schedule and print emails at 
correct dates based on provided rules.

Input:
1. sendSchedule - Map from relative day offsets to email message types:
   * "start" → send on subscription start day
   * Negative integers (e.g., "-15") → send N days before subscription end
   * "end" → send on subscription end date

2. userAccounts - List of user objects with:
   * name: string - user name
   * plan: string - subscription plan name
   * account_date: int - day subscription started
   * duration: int - duration of subscription in days

Output:
Print one line per email event in ascending order of day:
  <day>: [<Email Type>] Subscription for <name> (<plan>)

Example:
sendSchedule = {
  "start": "Welcome",
  "-15": "Upcoming Expiry",
  "end": "Expired"
}

userAccounts = [
  {name: "Alice", plan: "Basic", account_date: 1, duration: 30}
]

Output:
1: [Welcome] Subscription for Alice (Basic)
16: [Upcoming Expiry] Subscription for Alice (Basic)  // day 31-15=16
31: [Expired] Subscription for Alice (Basic)  // day 1+30=31

Note: End date is account_date + duration

=============================================================================
PART 2: HANDLING PLAN CHANGES
=============================================================================

Problem:
Handle plan change events. When a user changes plans:
* Print [Changed] message on change date
* Recalculate remaining duration relative to change date
* Schedule future notifications based on new plan and updated timeline

Input:
1. sendSchedule - same as Part 1
2. userAccounts - same as Part 1
3. changes - List of plan change events:
   * name: user name
   * new_plan: new plan name
   * change_date: day the plan changed

Rules:
* On change date, print: <day>: [Changed] Subscription for <n> (<old_plan> -> <new_plan>)
* Cancel future notifications from old plan
* Calculate new end date based on remaining duration
* Schedule notifications for new plan

Example:
userAccounts = [
  {name: "Alice", plan: "Basic", account_date: 1, duration: 30}
]

changes = [
  {name: "Alice", new_plan: "Premium", change_date: 15}
]

Original end: day 31
At day 15, remaining: 31 - 15 = 16 days
New end: 15 + 16 = 31

Output:
1: [Welcome] Subscription for Alice (Basic)
15: [Changed] Subscription for Alice (Basic -> Premium)
16: [Upcoming Expiry] Subscription for Alice (Premium)  // Now Premium
31: [Expired] Subscription for Alice (Premium)

=============================================================================
PART 3: RENEWALS
=============================================================================

Problem:
Handle renewal events that extend subscription duration. When user renews:
* Print [Renewed] message on renewal date
* Extend subscription by given number of days
* Reschedule future emails based on new end date

Input:
1. sendSchedule - same as Part 1
2. userAccounts - same as Part 1
3. changes - List containing TWO types of events:
   
   Plan Change: {name: string, new_plan: string, change_date: int}
   Renewal: {name: string, extension: int, change_date: int}

Output:
Include [Renewed] events:
  <day>: [Renewed] Subscription for <name> (<plan>)

Reschedule upcoming notifications based on new end date.

Example:
userAccounts = [
  {name: "Bob", plan: "Pro", account_date: 1, duration: 30}
]

changes = [
  {name: "Bob", extension: 15, change_date: 20}  // Renewal
]

Original end: day 31
At day 20, renewed by 15 days
New end: 31 + 15 = 46

Output:
1: [Welcome] Subscription for Bob (Pro)
16: [Upcoming Expiry] Subscription for Bob (Pro)  // Original (31-15)
20: [Renewed] Subscription for Bob (Pro)
31: [Upcoming Expiry] Subscription for Bob (Pro)  // Rescheduled (46-15)
46: [Expired] Subscription for Bob (Pro)

Constraints:
- 1 <= number of users <= 100
- 1 <= duration <= 10000 days
- 1 <= account_date <= 10000
- Email types in sendSchedule are arbitrary strings
- Change dates are always >= account_date
- Multiple users can have events on the same day (output in name order)
*/

// helper class 

class User {
    String name;
    String plan;
    int startDate;
    int duration;

    User(String name, String plan, int startDate, int duration) {
        this.name = name;
        this.plan = plan;
        this.startDate = startDate;
        this.duration = duration;
    }
}

class Notification implements Runnable, Comparable<Notification> {

    public String notification;
    Integer date;

    Notification(int date, String notification) {
        this.date = date;
        this.notification = String.format("%d: %s", date, notification);
    }

    String getMessage() {
        return notification;
    }

    @Override
    public void run() {
        // do nothing
    }

    @Override
    public int compareTo(Notification second) {
        if (date == second.date)
            return this.getMessage().compareTo(second.getMessage());
        else
            return Integer.compare(date, second.date);
    }
}

class UserNotification extends Notification {

    User user;
    String type;

    UserNotification(int date, User u, String type) {
        super(date, "");
        this.type = type;
        user = u;
    }

    String getMessage() {
        notification = String.format("%d: [%s] Subscription for %s (%s)", date, type, user.name, user.plan);
        return notification;
    }

    @Override
    public int compareTo(Notification second) {
        if (date == second.date)
            return this.getMessage().compareTo(second.getMessage());
        else
            return Integer.compare(date, second.date);
    }
}

class UpdateUserNotification extends UserNotification {
    String updatedPlan;

    UpdateUserNotification(int date, User u, String type, String updatedValue) {
        super(date, u, type);
        this.updatedPlan = updatedValue;
    }

    String getMessage() {
        notification = String.format("%d: [%s] Subscription for %s (%s -> %s)", date, type, user.name, user.plan,
                this.updatedPlan);
        return notification;
    }

    public void run() {
        this.user.plan = updatedPlan;
    }
}

// ============= SOLUTION CLASS =============
class Solution {
    /**
     * Schedule subscription notifications based on plans and changes.
     * 
     * @param sendSchedule Map of day offsets to email types
     *                     "start" -> send at start
     *                     "-15" -> send 15 days before end
     *                     "end" -> send at end
     * @param userAccounts List of user subscription data
     * @param changes      List of plan changes and renewals (empty for Part 1)
     * @return List of notification strings in chronological order
     */
    public List<String> scheduleNotifications(
            Map<String, String> sendSchedule,
            List<Map<String, Object>> userAccounts,
            List<Map<String, Object>> changes) {

        /**
         * {-15=Upcoming Expiry, start=Welcome, end=Expired}
         * [{duration=30, account_date=1, name=Alice, plan=Pro}]
         * [{extension=30, change_date=20, name=Alice}]
         */
        System.out.println(sendSchedule);
        System.out.println(userAccounts);
        System.out.println(changes);

        PriorityQueue<Notification> q = new PriorityQueue<>();
        Map<String, User> userRepo = new HashMap<>();

        for (var row : userAccounts) {
            Integer date = (Integer) row.get("account_date");
            String name = (String) row.get("name");
            String plan = (String) row.get("plan");
            Integer duration = (Integer) row.get("duration");
            User u = new User(name, plan, date, duration);
            userRepo.put(name, u);

            for (var schedule : sendSchedule.entrySet()) {
                if (schedule.getKey().equals("start")) {
                    q.offer(new UserNotification(date, u, schedule.getValue()));
                } else if (schedule.getKey().equals("end")) {
                    q.offer(new UserNotification(date + duration, u, schedule.getValue()));
                } else {
                    int time = date + duration + Integer.parseInt(schedule.getKey());
                    if (time < date)
                        continue;
                    q.offer(new UserNotification(time, u, schedule.getValue()));
                }
            }
        }

        for (var row : changes) {
            Integer date = (Integer) row.get("change_date");
            String name = (String) row.get("name");
            String plan = (String) row.get("new_plan");
            User u = userRepo.get(name);
            q.offer(new UpdateUserNotification(date, u, "Changed", plan));
        }

        List<String> notifications = new ArrayList<>();

        while (!q.isEmpty()) {
            var top = q.poll();
            notifications.add(top.getMessage());
            top.run();
        }

        return notifications;
    }
}

// ============= JUDGE CLASS =============
public class Judge {
    // ========== CONFIGURATION FLAGS ==========
    private static final int PART = 3; // Which part to test: 1, 2, or 3
    private static final boolean CHECK_FULL = true; // true = all tests, false = basic only
    private static final int[] SELECTED_TESTS = { 1 }; // Empty = all, or specify: {1, 3, 5}

    private static int passedTests = 0;
    private static int totalTests = 0;
    private static int currentTestNumber = 0;
    private static Set<Integer> selectedTestSet = new HashSet<>();

    public static void main(String[] args) {
        Solution solution = new Solution();

        // Initialize selected tests
        for (int testNum : SELECTED_TESTS) {
            selectedTestSet.add(testNum);
        }

        System.out.println("=".repeat(70));
        System.out.println("SUBSCRIPTION NOTIFICATION SCHEDULER");
        System.out.println("Difficulty: MEDIUM");
        System.out.println("=".repeat(70));
        System.out.println("Testing: PART " + PART);
        System.out.println("Mode: " + (CHECK_FULL ? "FULL TEST (All Cases)" : "BASIC TEST"));

        if (SELECTED_TESTS.length > 0) {
            System.out.println("Selected Tests: " + Arrays.toString(SELECTED_TESTS));
        } else {
            System.out.println("Running: ALL TESTS");
        }
        System.out.println("=".repeat(70));

        // Run tests based on selected part
        switch (PART) {
            case 1:
                runPart1Tests(solution);
                break;
            case 2:
                runPart2Tests(solution);
                break;
            case 3:
                runPart3Tests(solution);
                break;
            default:
                System.out.println("❌ Invalid PART selected. Choose 1, 2, or 3.");
                return;
        }

        // Print summary
        printSummary();
    }

    // ============= PART 1 TESTS =============
    private static void runPart1Tests(Solution solution) {
        System.out.println("\n=== PART 1: BASIC EMAIL SCHEDULING ===\n");

        System.out.println("--- Basic Tests ---");

        // Test 1: Single user, basic schedule
        Map<String, String> schedule1 = new HashMap<>();
        schedule1.put("start", "Welcome");
        schedule1.put("-15", "Upcoming Expiry");
        schedule1.put("end", "Expired");

        List<Map<String, Object>> users1 = new ArrayList<>();
        Map<String, Object> alice = new HashMap<>();
        alice.put("name", "Alice");
        alice.put("plan", "Basic");
        alice.put("account_date", 1);
        alice.put("duration", 30);
        users1.add(alice);

        test(
                () -> solution.scheduleNotifications(schedule1, users1, new ArrayList<>()),
                Arrays.asList(
                        "1: [Welcome] Subscription for Alice (Basic)",
                        "16: [Upcoming Expiry] Subscription for Alice (Basic)",
                        "31: [Expired] Subscription for Alice (Basic)"),
                "Part 1: Single user with start, -15, end");

        // Test 2: Multiple users
        List<Map<String, Object>> users2 = new ArrayList<>();
        Map<String, Object> bob = new HashMap<>();
        bob.put("name", "Bob");
        bob.put("plan", "Pro");
        bob.put("account_date", 5);
        bob.put("duration", 20);
        users2.add(alice);
        users2.add(bob);

        test(
                () -> solution.scheduleNotifications(schedule1, users2, new ArrayList<>()),
                Arrays.asList(
                        "1: [Welcome] Subscription for Alice (Basic)",
                        "5: [Welcome] Subscription for Bob (Pro)",
                        "10: [Upcoming Expiry] Subscription for Bob (Pro)",
                        "16: [Upcoming Expiry] Subscription for Alice (Basic)",
                        "25: [Expired] Subscription for Bob (Pro)",
                        "31: [Expired] Subscription for Alice (Basic)"),
                "Part 1: Multiple users, chronological order");

        // Test 3: Same day, alphabetical ordering
        List<Map<String, Object>> users3 = new ArrayList<>();
        Map<String, Object> charlie = new HashMap<>();
        charlie.put("name", "Charlie");
        charlie.put("plan", "Basic");
        charlie.put("account_date", 1);
        charlie.put("duration", 30);
        users3.add(charlie);
        users3.add(alice);

        test(
                () -> solution.scheduleNotifications(schedule1, users3, new ArrayList<>()),
                Arrays.asList(
                        "1: [Welcome] Subscription for Alice (Basic)",
                        "1: [Welcome] Subscription for Charlie (Basic)",
                        "16: [Upcoming Expiry] Subscription for Alice (Basic)",
                        "16: [Upcoming Expiry] Subscription for Charlie (Basic)",
                        "31: [Expired] Subscription for Alice (Basic)",
                        "31: [Expired] Subscription for Charlie (Basic)"),
                "Part 1: Same day events, alphabetical order");

        // Test 4: Multiple offset types
        Map<String, String> schedule4 = new HashMap<>();
        schedule4.put("start", "Welcome");
        schedule4.put("-30", "First Warning");
        schedule4.put("-15", "Second Warning");
        schedule4.put("-7", "Final Warning");
        schedule4.put("end", "Expired");

        List<Map<String, Object>> users4 = new ArrayList<>();
        Map<String, Object> dave = new HashMap<>();
        dave.put("name", "Dave");
        dave.put("plan", "Premium");
        dave.put("account_date", 1);
        dave.put("duration", 60);
        users4.add(dave);

        test(
                () -> solution.scheduleNotifications(schedule4, users4, new ArrayList<>()),
                Arrays.asList(
                        "1: [Welcome] Subscription for Dave (Premium)",
                        "31: [First Warning] Subscription for Dave (Premium)",
                        "46: [Second Warning] Subscription for Dave (Premium)",
                        "54: [Final Warning] Subscription for Dave (Premium)",
                        "61: [Expired] Subscription for Dave (Premium)"),
                "Part 1: Multiple warning offsets");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 5: Short duration (warnings might be before start)
            Map<String, String> schedule5 = new HashMap<>();
            schedule5.put("start", "Welcome");
            schedule5.put("-30", "Warning");
            schedule5.put("end", "Expired");

            List<Map<String, Object>> users5 = new ArrayList<>();
            Map<String, Object> eve = new HashMap<>();
            eve.put("name", "Eve");
            eve.put("plan", "Trial");
            eve.put("account_date", 10);
            eve.put("duration", 7);
            users5.add(eve);

            test(
                    () -> solution.scheduleNotifications(schedule5, users5, new ArrayList<>()),
                    Arrays.asList(
                            "10: [Welcome] Subscription for Eve (Trial)",
                            "17: [Expired] Subscription for Eve (Trial)"
                    // No warning at day -13 (before start)
                    ),
                    "Edge: Warning offset beyond start date (skip negative days)");

            // Test 6: Duration of 1 day
            List<Map<String, Object>> users6 = new ArrayList<>();
            Map<String, Object> frank = new HashMap<>();
            frank.put("name", "Frank");
            frank.put("plan", "OneDay");
            frank.put("account_date", 1);
            frank.put("duration", 1);
            users6.add(frank);

            test(
                    () -> solution.scheduleNotifications(schedule1, users6, new ArrayList<>()),
                    Arrays.asList(
                            "1: [Welcome] Subscription for Frank (OneDay)",
                            "2: [Expired] Subscription for Frank (OneDay)"
                    // -15 offset would be negative, skip it
                    ),
                    "Edge: Duration of 1 day");
        }
    }

    // ============= PART 2 TESTS =============
    private static void runPart2Tests(Solution solution) {
        System.out.println("\n=== PART 2: HANDLING PLAN CHANGES ===\n");

        System.out.println("--- Basic Tests ---");

        Map<String, String> schedule = new HashMap<>();
        schedule.put("start", "Welcome");
        schedule.put("-15", "Upcoming Expiry");
        schedule.put("end", "Expired");

        // Test 1: Single plan change
        List<Map<String, Object>> users1 = new ArrayList<>();
        Map<String, Object> alice = new HashMap<>();
        alice.put("name", "Alice");
        alice.put("plan", "Basic");
        alice.put("account_date", 1);
        alice.put("duration", 30);
        users1.add(alice);

        List<Map<String, Object>> changes1 = new ArrayList<>();
        Map<String, Object> change1 = new HashMap<>();
        change1.put("name", "Alice");
        change1.put("new_plan", "Premium");
        change1.put("change_date", 15);
        changes1.add(change1);

        test(
                () -> solution.scheduleNotifications(schedule, users1, changes1),
                Arrays.asList(
                        "1: [Welcome] Subscription for Alice (Basic)",
                        "15: [Changed] Subscription for Alice (Basic -> Premium)",
                        "16: [Upcoming Expiry] Subscription for Alice (Premium)",
                        "31: [Expired] Subscription for Alice (Premium)"),
                "Part 2: Single plan change mid-subscription");

        // Test 2: Plan change before first warning
        List<Map<String, Object>> users2 = new ArrayList<>();
        Map<String, Object> bob = new HashMap<>();
        bob.put("name", "Bob");
        bob.put("plan", "Basic");
        bob.put("account_date", 1);
        bob.put("duration", 30);
        users2.add(bob);

        List<Map<String, Object>> changes2 = new ArrayList<>();
        Map<String, Object> change2 = new HashMap<>();
        change2.put("name", "Bob");
        change2.put("new_plan", "Pro");
        change2.put("change_date", 10);
        changes2.add(change2);

        test(
                () -> solution.scheduleNotifications(schedule, users2, changes2),
                Arrays.asList(
                        "1: [Welcome] Subscription for Bob (Basic)",
                        "10: [Changed] Subscription for Bob (Basic -> Pro)",
                        "16: [Upcoming Expiry] Subscription for Bob (Pro)",
                        "31: [Expired] Subscription for Bob (Pro)"),
                "Part 2: Change before any warnings");

        // Test 3: Plan change after warning sent
        List<Map<String, Object>> users3 = new ArrayList<>();
        Map<String, Object> charlie = new HashMap<>();
        charlie.put("name", "Charlie");
        charlie.put("plan", "Basic");
        charlie.put("account_date", 1);
        charlie.put("duration", 30);
        users3.add(charlie);

        List<Map<String, Object>> changes3 = new ArrayList<>();
        Map<String, Object> change3 = new HashMap<>();
        change3.put("name", "Charlie");
        change3.put("new_plan", "Enterprise");
        change3.put("change_date", 20);
        changes3.add(change3);

        test(
                () -> solution.scheduleNotifications(schedule, users3, changes3),
                Arrays.asList(
                        "1: [Welcome] Subscription for Charlie (Basic)",
                        "16: [Upcoming Expiry] Subscription for Charlie (Basic)",
                        "20: [Changed] Subscription for Charlie (Basic -> Enterprise)",
                        "31: [Expired] Subscription for Charlie (Enterprise)"),
                "Part 2: Change after warning already sent");

        // Test 4: Multiple users with changes
        List<Map<String, Object>> users4 = new ArrayList<>();
        users4.add(alice);
        users4.add(bob);

        List<Map<String, Object>> changes4 = new ArrayList<>();
        Map<String, Object> change4a = new HashMap<>();
        change4a.put("name", "Alice");
        change4a.put("new_plan", "Pro");
        change4a.put("change_date", 15);
        changes4.add(change4a);

        Map<String, Object> change4b = new HashMap<>();
        change4b.put("name", "Bob");
        change4b.put("new_plan", "Premium");
        change4b.put("change_date", 20);
        changes4.add(change4b);

        test(
                () -> solution.scheduleNotifications(schedule, users4, changes4),
                Arrays.asList(
                        "1: [Welcome] Subscription for Alice (Basic)",
                        "1: [Welcome] Subscription for Bob (Basic)",
                        "15: [Changed] Subscription for Alice (Basic -> Pro)",
                        "16: [Upcoming Expiry] Subscription for Alice (Pro)",
                        "16: [Upcoming Expiry] Subscription for Bob (Basic)",
                        "20: [Changed] Subscription for Bob (Basic -> Premium)",
                        "31: [Expired] Subscription for Alice (Pro)",
                        "31: [Expired] Subscription for Bob (Premium)"),
                "Part 2: Multiple users with plan changes");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 5: Multiple changes for same user
            List<Map<String, Object>> users5 = new ArrayList<>();
            Map<String, Object> dave = new HashMap<>();
            dave.put("name", "Dave");
            dave.put("plan", "Basic");
            dave.put("account_date", 1);
            dave.put("duration", 60);
            users5.add(dave);

            List<Map<String, Object>> changes5 = new ArrayList<>();
            Map<String, Object> change5a = new HashMap<>();
            change5a.put("name", "Dave");
            change5a.put("new_plan", "Pro");
            change5a.put("change_date", 20);
            changes5.add(change5a);

            Map<String, Object> change5b = new HashMap<>();
            change5b.put("name", "Dave");
            change5b.put("new_plan", "Premium");
            change5b.put("change_date", 40);
            changes5.add(change5b);

            test(
                    () -> solution.scheduleNotifications(schedule, users5, changes5),
                    Arrays.asList(
                            "1: [Welcome] Subscription for Dave (Basic)",
                            "20: [Changed] Subscription for Dave (Basic -> Pro)",
                            "40: [Changed] Subscription for Dave (Pro -> Premium)",
                            "46: [Upcoming Expiry] Subscription for Dave (Premium)",
                            "61: [Expired] Subscription for Dave (Premium)"),
                    "Edge: Multiple plan changes for same user");
        }
    }

    // ============= PART 3 TESTS =============
    private static void runPart3Tests(Solution solution) {
        System.out.println("\n=== PART 3: RENEWALS ===\n");

        System.out.println("--- Basic Tests ---");

        Map<String, String> schedule = new HashMap<>();
        schedule.put("start", "Welcome");
        schedule.put("-15", "Upcoming Expiry");
        schedule.put("end", "Expired");

        // Test 1: Single renewal
        List<Map<String, Object>> users1 = new ArrayList<>();
        Map<String, Object> alice = new HashMap<>();
        alice.put("name", "Alice");
        alice.put("plan", "Pro");
        alice.put("account_date", 1);
        alice.put("duration", 30);
        users1.add(alice);

        List<Map<String, Object>> changes1 = new ArrayList<>();
        Map<String, Object> renewal1 = new HashMap<>();
        renewal1.put("name", "Alice");
        renewal1.put("extension", 30);
        renewal1.put("change_date", 20);
        changes1.add(renewal1);

        test(
                () -> solution.scheduleNotifications(schedule, users1, changes1),
                Arrays.asList(
                        "1: [Welcome] Subscription for Alice (Pro)",
                        "16: [Upcoming Expiry] Subscription for Alice (Pro)",
                        "20: [Renewed] Subscription for Alice (Pro)",
                        "46: [Upcoming Expiry] Subscription for Alice (Pro)",
                        "61: [Expired] Subscription for Alice (Pro)"),
                "Part 3: Single renewal extends subscription");

        // Test 2: Renewal before expiry warning
        List<Map<String, Object>> users2 = new ArrayList<>();
        Map<String, Object> bob = new HashMap<>();
        bob.put("name", "Bob");
        bob.put("plan", "Basic");
        bob.put("account_date", 1);
        bob.put("duration", 30);
        users2.add(bob);

        List<Map<String, Object>> changes2 = new ArrayList<>();
        Map<String, Object> renewal2 = new HashMap<>();
        renewal2.put("name", "Bob");
        renewal2.put("extension", 15);
        renewal2.put("change_date", 10);
        changes2.add(renewal2);

        test(
                () -> solution.scheduleNotifications(schedule, users2, changes2),
                Arrays.asList(
                        "1: [Welcome] Subscription for Bob (Basic)",
                        "10: [Renewed] Subscription for Bob (Basic)",
                        "31: [Upcoming Expiry] Subscription for Bob (Basic)",
                        "46: [Expired] Subscription for Bob (Basic)"),
                "Part 3: Renewal before expiry warning");

        // Test 3: Mix of plan change and renewal
        List<Map<String, Object>> users3 = new ArrayList<>();
        Map<String, Object> charlie = new HashMap<>();
        charlie.put("name", "Charlie");
        charlie.put("plan", "Basic");
        charlie.put("account_date", 1);
        charlie.put("duration", 30);
        users3.add(charlie);

        List<Map<String, Object>> changes3 = new ArrayList<>();
        Map<String, Object> change3 = new HashMap<>();
        change3.put("name", "Charlie");
        change3.put("new_plan", "Pro");
        change3.put("change_date", 10);
        changes3.add(change3);

        Map<String, Object> renewal3 = new HashMap<>();
        renewal3.put("name", "Charlie");
        renewal3.put("extension", 20);
        renewal3.put("change_date", 20);
        changes3.add(renewal3);

        test(
                () -> solution.scheduleNotifications(schedule, users3, changes3),
                Arrays.asList(
                        "1: [Welcome] Subscription for Charlie (Basic)",
                        "10: [Changed] Subscription for Charlie (Basic -> Pro)",
                        "20: [Renewed] Subscription for Charlie (Pro)",
                        "36: [Upcoming Expiry] Subscription for Charlie (Pro)",
                        "51: [Expired] Subscription for Charlie (Pro)"),
                "Part 3: Plan change followed by renewal");

        // Test 4: Multiple renewals
        List<Map<String, Object>> users4 = new ArrayList<>();
        Map<String, Object> dave = new HashMap<>();
        dave.put("name", "Dave");
        dave.put("plan", "Premium");
        dave.put("account_date", 1);
        dave.put("duration", 30);
        users4.add(dave);

        List<Map<String, Object>> changes4 = new ArrayList<>();
        Map<String, Object> renewal4a = new HashMap<>();
        renewal4a.put("name", "Dave");
        renewal4a.put("extension", 10);
        renewal4a.put("change_date", 15);
        changes4.add(renewal4a);

        Map<String, Object> renewal4b = new HashMap<>();
        renewal4b.put("name", "Dave");
        renewal4b.put("extension", 10);
        renewal4b.put("change_date", 25);
        changes4.add(renewal4b);

        test(
                () -> solution.scheduleNotifications(schedule, users4, changes4),
                Arrays.asList(
                        "1: [Welcome] Subscription for Dave (Premium)",
                        "15: [Renewed] Subscription for Dave (Premium)",
                        "25: [Renewed] Subscription for Dave (Premium)",
                        "36: [Upcoming Expiry] Subscription for Dave (Premium)",
                        "51: [Expired] Subscription for Dave (Premium)"),
                "Part 3: Multiple renewals");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 5: Renewal on expiry day
            List<Map<String, Object>> users5 = new ArrayList<>();
            Map<String, Object> eve = new HashMap<>();
            eve.put("name", "Eve");
            eve.put("plan", "Basic");
            eve.put("account_date", 1);
            eve.put("duration", 30);
            users5.add(eve);

            List<Map<String, Object>> changes5 = new ArrayList<>();
            Map<String, Object> renewal5 = new HashMap<>();
            renewal5.put("name", "Eve");
            renewal5.put("extension", 30);
            renewal5.put("change_date", 31); // On expiry day
            changes5.add(renewal5);

            test(
                    () -> solution.scheduleNotifications(schedule, users5, changes5),
                    Arrays.asList(
                            "1: [Welcome] Subscription for Eve (Basic)",
                            "16: [Upcoming Expiry] Subscription for Eve (Basic)",
                            "31: [Expired] Subscription for Eve (Basic)",
                            "31: [Renewed] Subscription for Eve (Basic)",
                            "46: [Upcoming Expiry] Subscription for Eve (Basic)",
                            "61: [Expired] Subscription for Eve (Basic)"),
                    "Edge: Renewal on expiry day");

            // Test 6: Complex sequence
            List<Map<String, Object>> users6 = new ArrayList<>();
            Map<String, Object> frank = new HashMap<>();
            frank.put("name", "Frank");
            frank.put("plan", "Trial");
            frank.put("account_date", 1);
            frank.put("duration", 60);
            users6.add(frank);

            List<Map<String, Object>> changes6 = new ArrayList<>();
            Map<String, Object> change6 = new HashMap<>();
            change6.put("name", "Frank");
            change6.put("new_plan", "Basic");
            change6.put("change_date", 20);
            changes6.add(change6);

            Map<String, Object> renewal6 = new HashMap<>();
            renewal6.put("name", "Frank");
            renewal6.put("extension", 30);
            renewal6.put("change_date", 40);
            changes6.add(renewal6);

            Map<String, Object> change6b = new HashMap<>();
            change6b.put("name", "Frank");
            change6b.put("new_plan", "Pro");
            change6b.put("change_date", 60);
            changes6.add(change6b);

            test(
                    () -> solution.scheduleNotifications(schedule, users6, changes6),
                    Arrays.asList(
                            "1: [Welcome] Subscription for Frank (Trial)",
                            "20: [Changed] Subscription for Frank (Trial -> Basic)",
                            "40: [Renewed] Subscription for Frank (Basic)",
                            "46: [Upcoming Expiry] Subscription for Frank (Basic)",
                            "60: [Changed] Subscription for Frank (Basic -> Pro)",
                            "76: [Upcoming Expiry] Subscription for Frank (Pro)",
                            "91: [Expired] Subscription for Frank (Pro)"),
                    "Edge: Complex sequence (change, renewal, change)");
        }
    }

    // ============= HELPER METHODS =============
    private static <T> void test(TestSupplier<T> supplier, T expected, String description) {
        currentTestNumber++;

        // Skip if this test is not in the selected set
        if (SELECTED_TESTS.length > 0 && !selectedTestSet.contains(currentTestNumber)) {
            return;
        }

        totalTests++;
        try {
            T result = supplier.get();
            boolean passed = Objects.deepEquals(result, expected);

            if (passed) {
                passedTests++;
                System.out.printf("✓ PASS [Test #%d]: %s%n", currentTestNumber, description);
            } else {
                System.out.printf("✗ FAIL [Test #%d]: %s%n", currentTestNumber, description);
                System.out.println("  Expected:");
                printList(expected);
                System.out.println("  Got:");
                printList(result);
            }
        } catch (Exception e) {
            System.out.printf("✗ ERROR [Test #%d]: %s%n", currentTestNumber, description);
            System.out.println("  Exception: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printList(Object obj) {
        if (obj instanceof List) {
            List<?> list = (List<?>) obj;
            for (Object item : list) {
                System.out.println("    " + item);
            }
        } else {
            System.out.println("    " + obj);
        }
    }

    private static void printSummary() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("TEST SUMMARY - PART " + PART);
        System.out.println("=".repeat(70));

        if (SELECTED_TESTS.length > 0) {
            System.out.printf("Selected: %d test(s) from %d total%n",
                    totalTests, currentTestNumber);
        }

        System.out.printf("Passed: %d/%d tests%n", passedTests, totalTests);

        if (passedTests == totalTests) {
            System.out.println("✓ All tests passed! 🎉");
        } else {
            System.out.printf("✗ %d test(s) failed%n", totalTests - passedTests);
        }

        System.out.println("\n" + "ℹ".repeat(70));
        if (!CHECK_FULL) {
            System.out.println("ℹ Set CHECK_FULL = true for edge cases");
        }
        if (SELECTED_TESTS.length == 0) {
            System.out.println("ℹ Set SELECTED_TESTS = new int[]{1,2,3} to run specific tests");
        }
        if (PART < 3) {
            System.out.println("ℹ Set PART = " + (PART + 1) + " to test next part");
        }
    }

    @FunctionalInterface
    interface TestSupplier<T> {
        T get() throws Exception;
    }
}

// ============= ALGORITHM HINTS =============
/*
 * HOW TO USE THE JUDGE:
 * ====================
 * 1. PART = 1, 2, or 3 → Select which part to test
 * 2. CHECK_FULL = false/true → Basic or comprehensive tests
 * 3. SELECTED_TESTS = {} → Run all, or specify: {1, 3, 5}
 * 
 * IMPLEMENTATION APPROACH:
 * ========================
 * 
 * DATA STRUCTURES:
 * ----------------
 * class Notification {
 * int day;
 * String type; // Email type, "Changed", "Renewed"
 * String name;
 * String plan;
 * String oldPlan; // For "Changed" events
 * }
 * 
 * class Subscription {
 * String name;
 * String plan;
 * int startDate;
 * int endDate;
 * }
 * 
 * List<Notification> allNotifications;
 * Map<String, Subscription> subscriptions;
 * 
 * PART 1 - BASIC SCHEDULING:
 * ---------------------------
 * 1. For each user:
 * - Calculate end_date = account_date + duration
 * 
 * 2. For each entry in sendSchedule:
 * - If "start": schedule at account_date
 * - If "end": schedule at end_date
 * - If "-N":
 * Calculate day = end_date - N
 * Only schedule if day >= account_date (skip negative days)
 * 
 * 3. Sort all notifications by:
 * - Primary: day (ascending)
 * - Secondary: name (alphabetical)
 * 
 * 4. Format output: "<day>: [<type>] Subscription for <name> (<plan>)"
 * 
 * PART 2 - PLAN CHANGES:
 * -----------------------
 * 1. Process all user accounts (same as Part 1)
 * 
 * 2. Sort changes by change_date
 * 
 * 3. For each change:
 * - Find user's subscription
 * - Calculate remaining_days = original_end_date - change_date
 * - New end_date = change_date + remaining_days
 * - Add [Changed] notification at change_date
 * - Cancel future notifications for old plan (after change_date)
 * - Schedule new notifications with new plan and new end_date
 * 
 * 4. Sort and output all notifications
 * 
 * PART 3 - RENEWALS:
 * ------------------
 * 1. Distinguish between plan changes and renewals:
 * - Plan change: has "new_plan" key
 * - Renewal: has "extension" key
 * 
 * 2. For renewals:
 * - Add [Renewed] notification at change_date
 * - Extend end_date by extension: end_date += extension
 * - Cancel future notifications (after renewal_date)
 * - Reschedule notifications with new end_date
 * 
 * 3. For plan changes: same as Part 2
 * 
 * 4. Handle multiple events for same user:
 * - Process events in chronological order
 * - Update end_date progressively
 * - Track current plan
 * 
 * EDGE CASES:
 * -----------
 * - Negative day calculations (skip if before start)
 * - Duration of 1 day
 * - Multiple events on same day (alphabetical by name)
 * - Renewal on expiry day
 * - Multiple changes/renewals for same user
 * - Change after some notifications already sent
 * 
 * OUTPUT FORMAT:
 * --------------
 * Normal: "<day>: [<EmailType>] Subscription for <name> (<plan>)"
 * Changed:
 * "<day>: [Changed] Subscription for <name> (<old_plan> -> <new_plan>)"
 * Renewed: "<day>: [Renewed] Subscription for <name> (<plan>)"
 * 
 * TIME COMPLEXITY: O(N * S + M log M)
 * where N = users, S = schedule entries, M = total notifications
 * SPACE COMPLEXITY: O(M) for storing all notifications
 * 
 * EXAMPLE WALKTHROUGH (PART 3):
 * ------------------------------
 * User: Alice, Basic, start=1, duration=30
 * Change: Renewal at day 20, extension=15
 * 
 * Step 1: Initial scheduling
 * - End date: 1 + 30 = 31
 * - Notifications: day 1 (Welcome), day 16 (Expiry -15), day 31 (Expired)
 * 
 * Step 2: Process renewal at day 20
 * - New end date: 31 + 15 = 46
 * - Add [Renewed] at day 20
 * - Cancel notifications after day 20: day 31 (Expired) cancelled
 * - Reschedule: day 31 (Expiry -15 from 46), day 46 (Expired)
 * 
 * Final output:
 * 1: [Welcome] Subscription for Alice (Basic)
 * 16: [Upcoming Expiry] Subscription for Alice (Basic)
 * 20: [Renewed] Subscription for Alice (Basic)
 * 31: [Upcoming Expiry] Subscription for Alice (Basic)
 * 46: [Expired] Subscription for Alice (Basic)
 */