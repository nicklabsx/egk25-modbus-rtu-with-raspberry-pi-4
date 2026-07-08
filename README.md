# EGK25 Modbus RTU Integration with Raspberry Pi 4

Integration of a SCHUNK EGK25-MB-M-B electric gripper using a Raspberry Pi 4 as a Modbus RTU gateway.

This project was developed as part of a university robotics seminar. The goal was to connect a SCHUNK EGK25 gripper to a legacy UR5 CB3.1 robot environment without relying on the official SCHUNK URCap, which was not compatible with the older CB3 system.

---

## Overview

A Raspberry Pi 4 was used as an intermediate gateway between the robot environment and the gripper. Communication with the gripper was implemented via RS-485 and Modbus RTU.

The project focused on register-based communication, command execution, status monitoring and fault handling.

---

## System Architecture

```text
UR5 CB3.1 Robot
      |
      | future robot-side interface
      |
Raspberry Pi 4
      |
      | RS-485 / Modbus RTU
      |
SCHUNK EGK25-MB-M-B Gripper
```

---

## Main Functions

* Modbus RTU communication with the gripper
* Status register reading
* Control command execution
* Error reset and acknowledgement handling
* Gripper enable sequence
* Command response and fault state monitoring

---

## Technologies

* SCHUNK EGK25-MB-M-B
* Universal Robots UR5 CB3.1
* Raspberry Pi 4
* RS-485 / USB adapter
* Modbus RTU
* Java
* Python
* URSim
* URCap SDK

---

## Results

The project successfully demonstrated direct Modbus RTU communication between the Raspberry Pi and the SCHUNK EGK25 gripper.

Key results:

* Stable RS-485 communication
* Successful Modbus register read/write operations
* Working error reset and acknowledgement sequence
* Gripper enable sequence
* Basic status monitoring and command validation

---

## Limitations

This project was developed for educational and experimental purposes.

It is not a certified industrial safety solution and does not replace the official SCHUNK integration tools. Further validation, safety assessment and production-level testing would be required before use in an industrial robot cell.

---

## Future Improvements

* Add a higher-level gripper command API
* Implement direct UR program integration
* Add structured logging and diagnostics
* Move register definitions into configuration files
* Continue toward a custom URCap-based integration concept

---

## Disclaimer

This project is not affiliated with or endorsed by SCHUNK or Universal Robots.
All product names and trademarks belong to their respective owners.
