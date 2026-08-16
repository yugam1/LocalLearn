import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.stream.Collectors;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Functional Requirements
 * ability to search movies by city,
 * list them out,
 * choose a theater, and then
 * select seats with a sort of reservation lock for a short period of time
 * before payment is completed.
 * User management
 * 
 * Non Functional Requirement
 * high availability on the search side and
 * strong consistency on the booking side
 * performance: moderate
 * Logging and monitoring
 */

/**
 * Core entities
 * 
 * BookingManager
 * Payment Service
 * Payment strategies
 * Booking
 * Shows
 * Theatre
 * Auditorium
 * Seats
 * User
 * Ticket
 * 
 * 
 * Relationships
 * 
 * User ticket composition
 * theatre auditorium composition
 * Shows auditorium association
 * auditorium seat composition
 * Shows seat aggregation?
 * Shows auditorium aggregation?
 * Booking ticket composition
 * Booking user association
 * BookingManager Booking 
 * PaymentService Paymentstrategy composition
 */

/**
 * API design
 * GET token /user/:user_id/validate => will be used for validation in
 * gateway/middleware
 * GET SearchShows /search/show/?type=movies?city=city
 * POST Reserve seats /show/?seats[]=id1??seats[]=id2?user=user1
 * POST BookSeats /show/?seats[]=id1??seats[]=id2?user=user1
 */

// Interface & enums
interface PaymentStrategy {
    boolean pay(int amount);
}

enum BookingState {
    AVAILABLE, RESERVED, CONFIRMED
}

enum SeatStatus {
    AVAILABLE, HOLD, UNAVAILABLE
}

// classes

// Payments
class UPIPayment implements PaymentStrategy {
    @Override
    public boolean pay(int amount) {
        System.out.println("Paid by UPI");
        return true;
    }
}

class CardPayment implements PaymentStrategy {
    @Override
    public boolean pay(int amount) {
        System.out.println("Paid by CARD");
        return true;
    }
}

@Data
class PaymentManager implements PaymentStrategy {
    PaymentStrategy strategy;

    @Override
    public boolean pay(int amount) {
        return strategy.pay(amount);
    }

    boolean payViaCard(int amount) {
        strategy = new CardPayment();
        return true;
    }

    boolean payViaUPI(int amount) {
        strategy = new UPIPayment();
        return true;
    }
}

// user management
@Data
@AllArgsConstructor
class User {
    Integer id;
    String name;
    String email;
    String passwString;
}

@NoArgsConstructor
class UserManager {
    List<User> users;

    boolean isValid(User u) {
        return users.contains(u);
    }
}

// core bms

@Data
@AllArgsConstructor
class Seat {
    Integer id;
    int row;
    int column;
    SeatStatus status;
    int seatPrice;
}

@NoArgsConstructor
@Data
class Auditorium {
    String name;
    Integer id;
    List<Seat> seats;
}

@NoArgsConstructor
@Data
class Theatre {
    String name;
    Integer id;
    List<Auditorium> audis;
    String city;
}

@Data
class Show {
    Theatre theatre;
    Auditorium audi;
    String showType;
    Instant showValidTill;
    SeatManager manager;

    boolean reserveSeat(List<Seat> seats, User u) {
        return manager.reserveSeat(seats, u);
    }

    boolean confirmSeat(List<Seat> seats, User u) {
        return manager.confirmSeat(seats, u);
    }
}

@AllArgsConstructor
@Data
class SeatLock {
    Seat seat;
    User user;
    Instant expiry;

    SeatLock(Seat s) {
        seat = s;
        user = null;
        expiry = Instant.now().plus(15, ChronoUnit.MINUTES);
    }

    boolean isExpired() {
        return Instant.now().isAfter(expiry);
    }
}

class SeatManager {
    Map<Integer, SeatLock> seatLocks;
    Map<Integer, SeatStatus> seatStatus;
    Lock masterLock;
    ScheduledExecutorService cleaner;

    SeatManager(Auditorium audi) {
        seatLocks = new ConcurrentHashMap<Integer, SeatLock>();
        masterLock = new ReentrantLock();
        for (Seat s : audi.getSeats()) {
            seatLocks.put(s.getId(), new SeatLock(s));
            seatStatus.put(s.getId(), SeatStatus.AVAILABLE);
        }
        cleaner = Executors.newSingleThreadScheduledExecutor();
        cleaner.scheduleAtFixedRate(() -> cleanLocks(), 1, 1, TimeUnit.MINUTES);
    }

    void cleanLocks() {
        for (var lk : seatLocks.entrySet()) {
            if (lk.getValue().isExpired()) {
                lk.getValue().getSeat().setStatus(SeatStatus.AVAILABLE);
                seatLocks.remove(lk.getKey());
            }
        }
    }

    boolean reserveSeat(List<Seat> seats, User u) {
        masterLock.lock();
        for (Seat s : seats) {
            if (seatStatus.get(s.getId()) != SeatStatus.AVAILABLE) {
                masterLock.unlock();
                return false;
            }
        }
        for (Seat s : seats) {
            seatStatus.put(s.getId(), SeatStatus.HOLD);
        }
        masterLock.unlock();
        return true;
    }

    boolean confirmSeat(List<Seat> seats, User u) {
        masterLock.lock();
        for (Seat s : seats) {
            seatStatus.put(s.getId(), SeatStatus.UNAVAILABLE);
        }
        masterLock.unlock();
        return true;
    }
}

/**
 * API design
 * GET token /user/:user_id/validate => will be used for validation in
 * gateway/middleware
 * GET SearchShows /search/show/?type=movies?city=city
 * POST Reserve seats /show/?seats[]=id1??seats[]=id2?user=user1
 * POST BookSeats /show/?seats[]=id1??seats[]=id2?user=user1
 */

class BookingManager {
    List<Show> activeShows;
    PaymentManager pmanager;

    BookingManager(List<Show> shows) {
        activeShows = shows;
        pmanager = new PaymentManager();
    }

    List<Show> getShows(String city, String type) {
        return activeShows.stream().filter(s -> s.getShowType().equals(type) && s.getTheatre().getCity().equals(city))
                .collect(Collectors.toList());

    }

    boolean reserveSeats(Show s, List<Seat> seats, User u) {
        return s.reserveSeat(seats, u);
    }

    boolean confirmSeats(Show s, List<Seat> seats, User u) {
        int total = seats.stream().mapToInt(seat -> seat.getSeatPrice()).sum();
        if (!pmanager.payViaUPI(total))
            return false;
        return s.reserveSeat(seats, u);
    }
}

public class BookMyShow {

    UserManager uManager;

    boolean validateUser(User u) {
        return uManager.isValid(u);
    }

    public static void main(String[] args) {

    }

}
