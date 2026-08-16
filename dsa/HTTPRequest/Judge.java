// ============= PROBLEM DESCRIPTION =============
/*
HTTP REQUEST REDIRECTION (Amazon HackerRank)

Platform: Amazon OA / HackerRank
Difficulty: MEDIUM

Problem:
Developers at Amazon are fixing a bug where HTTP requests are redirected to 
different servers.

Setup:
- n servers placed on an infinite 2D plane
- Each server has coordinates (x, y)
- q redirectRecords indicating redirection directions

Directions:
Requests redirect from server (a, b) using one of 4 directions (for arbitrary Z ≥ 1):
- Direction 1: (a, b) → (a + Z, b + Z)
- Direction 2: (a, b) → (a + Z, b - Z)
- Direction 3: (a, b) → (a - Z, b + Z)
- Direction 4: (a, b) → (a - Z, b - Z)

Rules:
1. Request is redirected to the NEAREST server in the given direction
2. Servers already visited are marked and CANNOT be revisited
3. If no servers exist in the direction, redirection is deemed invalid (skipped)
4. Request cannot be redirected to any previously visited server
5. Request always redirected towards nearest server (minimum distance Z)

Task:
Determine the final coordinates of the server to which the request is redirected.

Note: Request starts at the first server (locations[0])

Example:
locations = [[3, 4], [1, 2], [7, 8], [5, 6]]
redirectRecords = [1, 4]

Process:
1. Start at (3, 4), mark as visited
2. Direction 1: (a, b) → (a + Z, b + Z)
   - Unvisited servers in this direction: (5, 6) with Z=2, (7, 8) with Z=4
   - Nearest: (5, 6) with Z=2
   - Redirect to (5, 6), mark as visited

3. Now at (5, 6)
4. Direction 4: (a, b) → (a - Z, b - Z)
   - Unvisited servers: (1, 2) with Z=4, (3, 4) already visited
   - Nearest unvisited: (1, 2) with Z=4
   - Redirect to (1, 2)

Output: [1, 2]

Input Format:
- locations[n][2]: 2D array where locations[i] = [x, y]
- redirectRecords[q]: array of direction numbers (1, 2, 3, or 4)

Output:
- [x, y]: final coordinates after all redirects

Constraints:
- 1 <= n <= 10^5
- 1 <= q <= 10^5
*/

// ============= SOLUTION CLASS =============
import java.util.*;

class Solution {

    static class Server {
        int x, y;

        Server(int x, int y) {
            this.x = x;
            this.y = y;
        }
    }

    public int[] determineLocation(int[][] locations, int[] redirectRecords) {

        Map<Integer, TreeSet<Server>> diag1 = new HashMap<>();
        Map<Integer, TreeSet<Server>> diag2 = new HashMap<>();

        Comparator<Server> comp = (a, b) -> {
            if (a.x != b.x)
                return a.x - b.x;
            return a.y - b.y;
        };

        // Build diagonal maps
        for (int[] loc : locations) {
            int x = loc[0];
            int y = loc[1];

            Server s = new Server(x, y);

            diag1.computeIfAbsent(x - y, k -> new TreeSet<>(comp)).add(s);
            diag2.computeIfAbsent(x + y, k -> new TreeSet<>(comp)).add(s);
        }

        Server curr = new Server(locations[0][0], locations[0][1]);

        remove(curr, diag1, diag2);

        for (int dir : redirectRecords) {

            Server next = null;

            if (dir == 1) {
                TreeSet<Server> set = diag1.get(curr.x - curr.y);
                if (set != null)
                    next = set.higher(curr);
            }

            else if (dir == 4) {
                TreeSet<Server> set = diag1.get(curr.x - curr.y);
                if (set != null)
                    next = set.lower(curr);
            }

            else if (dir == 2) {
                TreeSet<Server> set = diag2.get(curr.x + curr.y);
                if (set != null)
                    next = set.higher(curr);
            }

            else if (dir == 3) {
                TreeSet<Server> set = diag2.get(curr.x + curr.y);
                if (set != null)
                    next = set.lower(curr);
            }

            if (next == null)
                continue;

            curr = next;
            remove(curr, diag1, diag2);
        }

        return new int[] { curr.x, curr.y };
    }

    private void remove(Server s,
            Map<Integer, TreeSet<Server>> d1,
            Map<Integer, TreeSet<Server>> d2) {

        TreeSet<Server> set1 = d1.get(s.x - s.y);
        if (set1 != null)
            set1.remove(s);

        TreeSet<Server> set2 = d2.get(s.x + s.y);
        if (set2 != null)
            set2.remove(s);
    }
}// ============= JUDGE CLASS =============

class Judge {
    private static final boolean CHECK_FULL = true;
    private static final int[] SELECTED_TESTS = {};

    private static int passedTests = 0;
    private static int totalTests = 0;
    private static int currentTestNumber = 0;
    private static Set<Integer> selectedTestSet = new HashSet<>();

    public static void main(String[] args) {
        Solution solution = new Solution();

        for (int testNum : SELECTED_TESTS) {
            selectedTestSet.add(testNum);
        }

        System.out.println("=".repeat(70));
        System.out.println("HTTP REQUEST REDIRECTION (Amazon)");
        System.out.println("Difficulty: MEDIUM");
        System.out.println("=".repeat(70));
        System.out.println("Mode: " + (CHECK_FULL ? "FULL TEST" : "BASIC TEST"));
        if (SELECTED_TESTS.length > 0) {
            System.out.println("Selected: " + Arrays.toString(SELECTED_TESTS));
        }
        System.out.println("=".repeat(70));

        runTests(solution);
        printSummary();
    }

    // ============= TESTS =============
    private static void runTests(Solution solution) {
        System.out.println("\n--- Basic Tests ---");

        // Test 1: Example from problem
        test(
                () -> solution.determineLocation(
                        new int[][] { { 3, 4 }, { 1, 2 }, { 7, 8 }, { 5, 6 } },
                        new int[] { 1, 4 }),
                new int[] { 1, 2 },
                "Sample: Start (3,4) → (5,6) → (1,2)");

        // Test 2: Single redirect
        test(
                () -> solution.determineLocation(
                        new int[][] { { 0, 0 }, { 2, 2 }, { 4, 4 } },
                        new int[] { 1 }),
                new int[] { 2, 2 },
                "Single redirect direction 1");

        // Test 3: No valid servers in direction
        test(
                () -> solution.determineLocation(
                        new int[][] { { 0, 0 }, { -1, -1 } },
                        new int[] { 1 }),
                new int[] { 0, 0 },
                "No servers in direction 1 (needs positive) → stay at (0,0)");

        // Test 4: Multiple redirects
        test(
                () -> solution.determineLocation(
                        new int[][] { { 0, 0 }, { 2, 2 }, { 4, 0 }, { 6, -2 } },
                        new int[] { 1, 2 }),
                new int[] { 4, 0 },
                "Dir 1: (0,0)→(2,2), Dir 2: (2,2)→(4,0)");

        // Test 5: Direction 3 and 4
        test(
                () -> solution.determineLocation(
                        new int[][] { { 5, 5 }, { 3, 7 }, { 2, 2 } },
                        new int[] { 3, 4 }),
                new int[] { 2, 2 },
                "Dir 3: (5,5)→(3,7), Dir 4: (3,7)→(2,2)");

        if (CHECK_FULL) {
            System.out.println("\n--- Edge Cases ---");

            // Test 6: Already visited server in path
            test(
                    () -> solution.determineLocation(
                            new int[][] { { 0, 0 }, { 1, 1 }, { 2, 2 } },
                            new int[] { 1, 1 }),
                    new int[] { 1, 1 },
                    "Edge: Second redirect finds (2,2) but already visited (0,0)");

            // Test 7: Multiple servers in same direction
            test(
                    () -> solution.determineLocation(
                            new int[][] { { 0, 0 }, { 1, 1 }, { 3, 3 }, { 5, 5 } },
                            new int[] { 1 }),
                    new int[] { 1, 1 },
                    "Edge: Multiple in direction, choose nearest");

            // Test 8: Single server (no redirects possible)
            test(
                    () -> solution.determineLocation(
                            new int[][] { { 5, 5 } },
                            new int[] { 1, 2, 3, 4 }),
                    new int[] { 5, 5 },
                    "Edge: Single server, all redirects fail → stay");
        }
    }

    // ============= HELPER METHODS =============
    private static void test(TestSupplier supplier, int[] expected, String desc) {
        currentTestNumber++;
        if (SELECTED_TESTS.length > 0 && !selectedTestSet.contains(currentTestNumber))
            return;

        totalTests++;
        try {
            int[] result = supplier.get();
            boolean passed = Arrays.equals(result, expected);

            if (passed) {
                passedTests++;
                System.out.printf("✓ PASS [#%d]: %s%n", currentTestNumber, desc);
            } else {
                System.out.printf("✗ FAIL [#%d]: %s%n", currentTestNumber, desc);
                System.out.println("  Expected: " + Arrays.toString(expected));
                System.out.println("  Got:      " + Arrays.toString(result));
            }
        } catch (Exception e) {
            System.out.printf("✗ ERROR [#%d]: %s - %s%n", currentTestNumber, desc, e.getMessage());
            e.printStackTrace();
        }
    }

    private static void printSummary() {
        System.out.println("\n" + "=".repeat(70));
        System.out.println("TEST SUMMARY");
        System.out.println("=".repeat(70));
        System.out.printf("Passed: %d/%d tests%n", passedTests, totalTests);

        if (passedTests == totalTests) {
            System.out.println("✓ All tests passed! 🎉");
        } else {
            System.out.printf("✗ %d test(s) failed%n", totalTests - passedTests);
        }

        if (!CHECK_FULL) {
            System.out.println("\nℹ Set CHECK_FULL = true for edge cases");
        }
        if (SELECTED_TESTS.length == 0) {
            System.out.println("ℹ Set SELECTED_TESTS = new int[]{1,2,3} to run specific tests");
        }
    }

    @FunctionalInterface
    interface TestSupplier {
        int[] get() throws Exception;
    }
}

// ============= ALGORITHM HINTS =============
/*
 * APPROACH - SIMULATION O(q × n):
 * ================================
 * 
 * int[] determineLocation(int[][] locations, int[] redirectRecords) {
 * int n = locations.length;
 * Set<Integer> visited = new HashSet<>();
 * 
 * // Start at first server
 * int currentIdx = 0;
 * visited.add(0);
 * int currX = locations[0][0];
 * int currY = locations[0][1];
 * 
 * // Process each redirect
 * for (int direction : redirectRecords) {
 * int nearestIdx = -1;
 * int minZ = Integer.MAX_VALUE;
 * 
 * // Find nearest server in given direction
 * for (int i = 0; i < n; i++) {
 * if (visited.contains(i)) continue;
 * 
 * int targetX = locations[i][0];
 * int targetY = locations[i][1];
 * 
 * if (isInDirection(currX, currY, targetX, targetY, direction)) {
 * int z = calculateZ(currX, currY, targetX, targetY);
 * if (z < minZ) {
 * minZ = z;
 * nearestIdx = i;
 * }
 * }
 * }
 * 
 * // Redirect if found
 * if (nearestIdx != -1) {
 * visited.add(nearestIdx);
 * currX = locations[nearestIdx][0];
 * currY = locations[nearestIdx][1];
 * }
 * // Else: invalid redirect, skip
 * }
 * 
 * return new int[]{currX, currY};
 * }
 * 
 * boolean isInDirection(int currX, int currY, int targetX, int targetY, int
 * dir) {
 * int dx = targetX - currX;
 * int dy = targetY - currY;
 * 
 * // Must have same absolute value (diagonal)
 * if (Math.abs(dx) != Math.abs(dy)) return false;
 * if (dx == 0) return false; // Same point
 * 
 * switch (dir) {
 * case 1: return dx > 0 && dy > 0; // Right-Up
 * case 2: return dx > 0 && dy < 0; // Right-Down
 * case 3: return dx < 0 && dy > 0; // Left-Up
 * case 4: return dx < 0 && dy < 0; // Left-Down
 * default: return false;
 * }
 * }
 * 
 * int calculateZ(int currX, int currY, int targetX, int targetY) {
 * return Math.abs(targetX - currX);
 * }
 * 
 * EXAMPLE WALKTHROUGH:
 * ====================
 * locations = [[3,4], [1,2], [7,8], [5,6]]
 * redirectRecords = [1, 4]
 * 
 * Step 1: Start at (3, 4), mark index 0 as visited
 * 
 * Step 2: Process direction 1 (a+Z, b+Z)
 * Check unvisited servers:
 * - (1, 2): dx=-2, dy=-2 → not in direction 1 (needs positive)
 * - (7, 8): dx=4, dy=4 → in direction 1! Z=4
 * - (5, 6): dx=2, dy=2 → in direction 1! Z=2 ✓ NEAREST
 * 
 * Redirect to (5, 6), mark index 3 as visited
 * Current: (5, 6)
 * 
 * Step 3: Process direction 4 (a-Z, b-Z)
 * Check unvisited servers:
 * - (1, 2): dx=-4, dy=-4 → in direction 4! Z=4 ✓ NEAREST
 * - (7, 8): dx=2, dy=2 → not in direction 4 (needs negative)
 * 
 * Redirect to (1, 2), mark index 1 as visited
 * Current: (1, 2)
 * 
 * Final: [1, 2]
 * 
 * EDGE CASES:
 * -----------
 * 1. No servers in given direction → skip redirect
 * 2. All servers already visited → skip redirect
 * 3. Multiple servers at same distance → any is acceptable
 * 4. Single server (no redirects possible)
 * 5. Redirect back to already visited → skip
 * 6. Direction with no diagonal matches
 * 
 * TIME COMPLEXITY: O(q × n) where q = redirects, n = servers
 * SPACE COMPLEXITY: O(n) for visited set
 * 
 * CRITICAL NOTES:
 * ---------------
 * - Directions are diagonal (dx == dy in absolute value)
 * - Distance Z = |dx| = |dy|
 * - Must track visited servers
 * - Skip invalid redirects (don't crash)
 * - Start at locations[0]
 */