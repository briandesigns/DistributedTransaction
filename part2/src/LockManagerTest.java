import LockManager.*;

/**
 * starts 2 Threads which repeatedly request for lock on items and unlocking them
 */
class LockManagerTest {
    public static void main(String[] args) {
        MyThread t1, t2;
        LockManager lm = new LockManager();
        t1 = new MyThread(lm, 1);
        t2 = new MyThread(lm, 2);
        t1.start();
        t2.start();
    }
}

class MyThread extends Thread {
    LockManager lm;
    int threadId;

    public MyThread(LockManager lm, int threadId) {
        this.lm = lm;
        this.threadId = threadId;
    }

    public void run() {
        if (threadId == 1) {
            try {
                lm.Lock(1, "a", LockManager.READ);
                System.out.println("thread1 requested READ lock on a");
            } catch (DeadlockException e) {
                System.out.println("Deadlock.... ");
            }

            try {
                this.sleep(4000);
            } catch (InterruptedException e) {
            }

            try {
                lm.Lock(1, "b", LockManager.WRITE);
                System.out.println("thread1 requested WRITE lock on b");
            } catch (DeadlockException e) {
                System.out.println("Deadlock.... ");
            }

            lm.UnlockAll(1);
        } else if (threadId == 2) {
            try {
                lm.Lock(2, "b", LockManager.READ);
                System.out.println("thread2 requested READ lock on b");
            } catch (DeadlockException e) {
                System.out.println("Deadlock.... ");
            }

            try {
                this.sleep(1000);
            } catch (InterruptedException e) {
            }

            try {
                lm.Lock(2, "a", LockManager.WRITE);
                System.out.println("thread2 requested WRITE lock on a");
            } catch (DeadlockException e) {
                System.out.println("Deadlock.... ");
            }

            lm.UnlockAll(2);
        }
    }
}
