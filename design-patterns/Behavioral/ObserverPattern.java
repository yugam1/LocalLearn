package Behavioral;

import java.util.HashSet;
import java.util.Set;

import lombok.AllArgsConstructor;

interface Observer {
    void update(String message);
}

interface Subject {
    void register(Observer o);

    void unregister(Observer o);

    void notifyOBservers();
}

@AllArgsConstructor
class YoutubeChannel implements Subject {
    String name;
    Set<Observer> subscribers;

    @Override
    public void register(Observer o) {
        subscribers.add(o);

    }

    @Override
    public void unregister(Observer o) {
        subscribers.remove(o);

    }

    @Override
    public void notifyOBservers() {
        for (Observer o : subscribers) {
            o.update("Video Added " + name);
        }

    }
}

@AllArgsConstructor
class Subscriber implements Observer {
    String name;

    @Override
    public void update(String message) {
        System.out.println(name + " " + message);
    }
}

public class ObserverPattern {
    public static void main(String[] args) {
        YoutubeChannel mychannel = new YoutubeChannel("MrYeast", new HashSet<>());
        Subscriber sub1 = new Subscriber("sub1");
        Subscriber sub2 = new Subscriber("sub2");
        mychannel.register(sub1);
        mychannel.register(sub2);
        mychannel.notifyOBservers();
    }
}
