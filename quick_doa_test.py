# -*- coding: utf-8 -*-
from __future__ import print_function
# quick_doa_test.py v5 - ASR Wakeup + Energy Polling
# Comments and documentation in English.

import sys
import time
import math
import qi

NAOQI_IP = "127.0.0.1"
NAOQI_PORT = 9559
POLL_SECONDS = 1.5 # Increased default for manual testing

def estimate_direction(front, rear, left, right):
    dx = float(left) - float(right)
    dy = float(front) - float(rear)
    azimuth_rad = math.atan2(dx, dy)
    azimuth_deg = azimuth_rad * 180.0 / math.pi
    mag = math.hypot(dx, dy)
    return azimuth_deg, mag

def main():
    try:
        if len(sys.argv) >= 3 and sys.argv[1] == "--duration":
            global POLL_SECONDS
            POLL_SECONDS = float(sys.argv[2])
    except Exception: pass

    session = None
    speech_sub_name = "doa_energy_test_{}".format(int(time.time()))

    try:
        session = qi.Session()
        print("[INFO] Connecting tcp://{}:{}".format(NAOQI_IP, NAOQI_PORT)); sys.stdout.flush()
        session.connect("tcp://{}:{}".format(NAOQI_IP, NAOQI_PORT))
        print("[OK] Session connected"); sys.stdout.flush()

        audio = session.service("ALAudioDevice")
        speech = session.service("ALSpeechRecognition")

        # 1. Wake up mics with ASR
        try:
            # ASR needs a language and vocabulary to work
            speech.setLanguage("English")
            speech.setVocabulary(["yes", "no"], False) # Minimal dummy vocabulary
            speech.subscribe(speech_sub_name)
            print("[INFO] Subscribed to ALSpeechRecognition ('{}') to activate mics.".format(speech_sub_name)); sys.stdout.flush()
        except Exception as e:
            print("[WARN] Could not subscribe to ASR: {}".format(e)); sys.stdout.flush()

        # 2. Enable energy computation
        try:
            audio.enableEnergyComputation()
            print("[INFO] Called enableEnergyComputation()"); sys.stdout.flush()
        except Exception as e:
            print("[WARN] Could not call enableEnergyComputation: {}".format(e)); sys.stdout.flush()
        
        # Give the audio pipeline a moment to start up
        time.sleep(0.2)

        energies = {"f": [], "r": [], "l": [], "rr": []}
        t0 = time.time()
        
        print("\nPolling mic energies for {} seconds... (make some noise!)".format(POLL_SECONDS)); sys.stdout.flush()
        
        while time.time() - t0 < POLL_SECONDS:
            try:
                f = audio.getFrontMicEnergy()
                r = audio.getRearMicEnergy()
                l = audio.getLeftMicEnergy()
                rr = audio.getRightMicEnergy()
                
                print("ENERGY F:{:<5.1f} R:{:<5.1f} L:{:<5.1f} R:{:<5.1f}".format(f, r, l, rr))
                sys.stdout.flush()
                
                energies["f"].append(f)
                energies["r"].append(r)
                energies["l"].append(l)
                energies["rr"].append(rr)
            except Exception as e:
                print("[WARN] Energy poll failed: {}".format(e)); sys.stdout.flush()
            time.sleep(0.1)

        # Use max energy value from the polling window for estimation
        f = max(energies["f"]) if energies["f"] else 0
        r = max(energies["r"]) if energies["r"] else 0
        l = max(energies["l"]) if energies["l"] else 0
        rr = max(energies["rr"]) if energies["rr"] else 0

        print("\n--- Results (Max Energy) ---"); sys.stdout.flush()
        print("Front: {:.1f}".format(f)); sys.stdout.flush()
        print("Rear:  {:.1f}".format(r)); sys.stdout.flush()
        print("Left:  {:.1f}".format(l)); sys.stdout.flush()
        print("Right: {:.1f}".format(rr)); sys.stdout.flush()

        az_deg, mag = estimate_direction(f, r, l, rr)
        print("\nEstimated Azimuth: {:.1f} deg (0=front, +90=left)".format(az_deg)); sys.stdout.flush()
        print("Magnitude: {:.1f}".format(mag)); sys.stdout.flush()

    except Exception as e:
        print("[ERR] Exception: {}".format(e)); sys.stdout.flush()
    finally:
        # 4. Clean up ASR subscription
        if session and session.isConnected():
            try:
                # Use a new proxy just in case the old one is stale
                speech_cleanup = session.service("ALSpeechRecognition")
                speech_cleanup.unsubscribe(speech_sub_name)
                print("[INFO] Unsubscribed from ALSpeechRecognition ('{}').".format(speech_sub_name)); sys.stdout.flush()
            except Exception as e:
                # This is expected if the subscription failed earlier
                pass
        if session: session.close()

if __name__ == "__main__":
    main()