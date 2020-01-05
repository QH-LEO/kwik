/*
 * Copyright © 2019, 2020 Peter Doornbosch
 *
 * This file is part of Kwik, a QUIC client Java library
 *
 * Kwik is free software: you can redistribute it and/or modify it under
 * the terms of the GNU Lesser General Public License as published by the
 * Free Software Foundation, either version 3 of the License, or (at your option)
 * any later version.
 *
 * Kwik is distributed in the hope that it will be useful, but
 * WITHOUT ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for
 * more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package net.luminis.quic;

import net.luminis.quic.log.Logger;
import net.luminis.quic.packet.PacketInfo;
import net.luminis.quic.packet.QuicPacket;

import java.time.Instant;
import java.util.List;
import java.util.stream.Stream;

// https://tools.ietf.org/html/draft-ietf-quic-recovery-23#section-6
// "QUIC's congestion control is based on TCP NewReno [RFC6582]."
public class NewRenoCongestionController extends AbstractCongestionController implements CongestionController {

    public enum Mode {
        SlowStart,
        CongestionAvoidance
    };

    // https://tools.ietf.org/html/draft-ietf-quic-recovery-23#appendix-B.1
    // "Reduction in congestion window when a new loss event is detected.  The RECOMMENDED value is 0.5."
    protected int kLossReductionFactor = 2;

    // https://tools.ietf.org/html/draft-ietf-quic-recovery-23#appendix-B.1
    // "Minimum congestion window in bytes.  The RECOMMENDED value is 2 * kMaxDatagramSize."
    protected int kMinimumWindow = 2 * 1200;

    protected int kMaxDatagramSize = 1200;

    private long slowStartThreshold = Long.MAX_VALUE;
    private Instant congestionRecoveryStartTime;

    public NewRenoCongestionController(Logger logger) {
        super(logger);
        congestionRecoveryStartTime = Instant.MIN;
    }

    @Override
    public synchronized void registerAcked(List<? extends PacketInfo> acknowlegdedPackets) {
        super.registerAcked(acknowlegdedPackets);

        // https://tools.ietf.org/html/draft-ietf-quic-recovery-23#section-6.4
        // "QUIC defines the end of recovery as a packet sent after the start of recovery being acknowledged"
        Stream<QuicPacket> notBeforeRecovery = acknowlegdedPackets.stream()
                .filter(ackedPacket -> ackedPacket.timeSent().isAfter(congestionRecoveryStartTime))
                .map(ackedPacket -> ackedPacket.packet());

        long previousCwnd = congestionWindow;
        notBeforeRecovery.forEach(p -> {
            if (congestionWindow < slowStartThreshold) {
                // i.e. mode is slow start
                congestionWindow += p.getSize();
            } else {
                // i.e. mode is congestion avoidance
                congestionWindow += kMaxDatagramSize * p.getSize() / congestionWindow;
            }
        });
        if (congestionWindow != previousCwnd) {
            log.cc("Cwnd(+): " + congestionWindow + " (" + getMode() + "); inflight: " + bytesInFlight);
        }
    }

    @Override
    public void registerLost(List<? extends PacketInfo> lostPackets) {
        super.registerLost(lostPackets);

        if (! lostPackets.isEmpty()) {
            PacketInfo largest = lostPackets.stream().max((p1, p2) -> p1.packet().getPacketNumber().compareTo(p2.packet().getPacketNumber())).get();
            fireCongestionEvent(largest.timeSent());
        }
    }

    private void fireCongestionEvent(Instant timeSent) {
        if (timeSent.isAfter(congestionRecoveryStartTime)) {
            congestionRecoveryStartTime = Instant.now();
            congestionWindow /= kLossReductionFactor;
            if (congestionWindow < kMinimumWindow) {
                congestionWindow = kMinimumWindow;
            }
            log.cc("Cwnd(-): " + congestionWindow + "; inflight: " + bytesInFlight);
            slowStartThreshold = congestionWindow;
        }
    }

    public Mode getMode() {
        if (congestionWindow < slowStartThreshold) {
            return Mode.SlowStart;
        }
        else {
            return Mode.CongestionAvoidance;
        }
    }

}

