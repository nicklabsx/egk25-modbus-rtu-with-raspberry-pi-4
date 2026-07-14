package org.example.egk25;

public class Main {
    public static void main(String[] args) {
        try (ModbusRtuClient rtu = new ModbusRtuClient();
             Egk25Controller egk = new Egk25Controller(rtu)) {

            rtu.open();

            System.out.println("Serial OK. Startup...");
            egk.startup();

            System.out.println("Alive loop running...");
            egk.startAliveLoop();
//////////////////////////////////////////////////////////            
            //DEMO BAUSTEIN HIER EINSETZEN
            for (int i = 0; i < 20; i++) {
                  egk.openGripper();
                  Thread.sleep(1000);
                  egk.closeGripper();
                  Thread.sleep(1000);
                  }
Thread.sleep(60_000);  //Timer an dem der Greifer IDLE ist
//////////////////////////////////////////////////////////

            Thread.sleep(60_000);

        } catch (Exception e) {
            e.printStackTrace();
            System.exit(1);
        }
    }
}
