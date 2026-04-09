# Arus by LHYS 🌊
**The Core Architecture of a High-Security AI-Fintech Platform for Indonesian MSMEs.**

[![Platform](https://img.shields.io/badge/Platform-Android-3DDC84?logo=android&logoColor=white)](#)
[![Kotlin](https://img.shields.io/badge/Language-Kotlin-7F52FF?logo=kotlin&logoColor=white)](#)
[![Security](https://img.shields.io/badge/Security-Post--Quantum%20Cryptography-blueviolet)](#)
[![License](https://img.shields.io/badge/Status-Proprietary-red)](#)

---

## 🚀 Vision: Humanize Technology
**LHYS (Lemmehearyasay)** is an AI-Fintech startup built on the philosophy of data sovereignty. In an era where MSME (UMKM) data is often exploited, Arus stands as a barrier. We don't just build a POS; we build a fortress for the people's economy.

---

## 🛠 Technical Showcase (The Engine)
This repository showcases the core architecture and high-level engineering modules of Arus. To protect our proprietary business logic and the **"Owner Edition"** integrity, core repositories, sync managers, and viewmodels are kept private.

### 🔐 1. Post-Quantum Cryptography (PQC)
We implemented **Kyber-768** to ensure that MSME data is secure even against future quantum computing threats.
* **Zero-Knowledge Architecture:** We don't own the data; the users do.
* **SecurityManager:** Advanced implementation of Android Keystore, PBKDF2 Master Password hashing, and AES-GCM encryption.

### 🧠 2. Coco AI: Parallel Reasoning
Powered by **Gemini 2.5 Flash**, our AI engine handles complex financial tasks through parallel reasoning.
* **Voice-to-Action:** Parsing human language into atomic financial journals.
* **Context-Aware Insights:** Real-time stock and revenue analysis without compromising speed.

### 🖨️ 3. Industrial Clean Hardware Engine
Custom-built Bluetooth Thermal Printer engine that handles raw byte streams (ESC/POS) with high efficiency.
* **Low-Level Rasterization:** Efficient image-to-bitmask conversion for logo printing.
* **Atomic Sync:** 100% accuracy in stock and journal synchronization.

---

## 🎨 Design Philosophy: Industrial Clean
We reject the "cluttered" UI of traditional fintech. Arus follows an **Industrial Clean** aesthetic:
* **Neo-Minimalism:** High contrast, sharp typography, and zero "bloat".
* **Native Performance:** Built 100% with Jetpack Compose. No Web-views. No Hybrid lag.
* **RAM Optimized:** Engineered to run smoothly on devices with limited resources, ensuring accessibility for all UMKM scales.

---

## 🏗 Tech Stack
| Layer | Tech |
| :--- | :--- |
| **Language** | Native Kotlin |
| **UI Framework** | Jetpack Compose |
| **AI Engine** | Gemini Flash (Parallel Reasoning) |
| **Cryptography** | PQC Kyber-768 |
| **Local Database** | Encrypted SQLite / Room |
| **Background Tasks** | Kotlin Coroutines & Flow |

---

## 📌 Repository Structure
This showcase focuses on the **Core** and **Design System** layers:
* `SecurityManager.kt`: The heart of our data protection.
* `ArusManager.kt`: Prompt engineering and AI reasoning logic.
* `PrinterManager.kt, ReportManager.kt and ExportManager.kt`: Printer and peripheral management.
* `ui/theme`: Custom UI components built from the ground up.

---

## 👨‍💻 Author
**Muhammad Rifky Firmansyah Sujana (Rifky)**
*Founder & CTO at LHYS*
*Digital PR Specialist | Native Android Architect*

*"Building technology that empowers, not exploits."*

---
© 2026 LHYS (Lemmehearyasay). All rights reserved. Proprietary Codebase.
