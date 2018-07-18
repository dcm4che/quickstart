/*
 * **** BEGIN LICENSE BLOCK *****
 * Version: MPL 1.1/GPL 2.0/LGPL 2.1
 *
 * The contents of this file are subject to the Mozilla Public License Version
 * 1.1 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 * http://www.mozilla.org/MPL/
 *
 * Software distributed under the License is distributed on an "AS IS" basis,
 * WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
 * for the specific language governing rights and limitations under the
 * License.
 *
 * The Original Code is part of dcm4che, an implementation of DICOM(TM) in
 * Java(TM), hosted at https://github.com/dcm4che.
 *
 * The Initial Developer of the Original Code is
 * J4Care.
 * Portions created by the Initial Developer are Copyright (C) 2015-2018
 * the Initial Developer. All Rights Reserved.
 *
 * Contributor(s):
 * See @authors listed below
 *
 * Alternatively, the contents of this file may be used under the terms of
 * either the GNU General Public License Version 2 or later (the "GPL"), or
 * the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
 * in which case the provisions of the GPL or the LGPL are applicable instead
 * of those above. If you wish to allow use of your version of this file only
 * under the terms of either the GPL or the LGPL, and not to allow others to
 * use your version of this file under the terms of the MPL, indicate your
 * decision by deleting the provisions above and replace them with the notice
 * and other provisions required by the GPL or the LGPL. If you do not delete
 * the provisions above, a recipient may use your version of this file under
 * the terms of any one of the MPL, the GPL or the LGPL.
 *
 * **** END LICENSE BLOCK *****
 *
 */

package org.dcm4che3.quickstart.ianscp;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.UID;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.net.service.*;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @since Jul 2018
 */
public class IANSCP {
    private Device device;
    private ApplicationEntity ae;
    private Connection conn;

    public IANSCP(String calledAET, String bindAddress, int port) {
        device = new Device("ian-scp");
        ae = new ApplicationEntity(calledAET);
        conn = new Connection(null, "127.0.0.1", port);
        conn.setBindAddress(bindAddress);
        device.addApplicationEntity(ae);
        device.addConnection(conn);
        ae.addConnection(conn);
        ae.addTransferCapability(new TransferCapability(null,
                UID.VerificationSOPClass, TransferCapability.Role.SCP, UID.ImplicitVRLittleEndian));
        ae.addTransferCapability(new TransferCapability(null,
                UID.InstanceAvailabilityNotificationSOPClass, TransferCapability.Role.SCP, UID.ImplicitVRLittleEndian));
        device.setDimseRQHandler(createServiceRegistry());
    }

    public void setExecutor(Executor executor) {
        device.setExecutor(executor);
    }

    public void setScheduledExecutor(ScheduledExecutorService executor) {
        device.setScheduledExecutor(executor);
    }

    public void start() throws IOException, GeneralSecurityException {
        device.bindConnections();
    }

    private DimseRQHandler createServiceRegistry() {
        DicomServiceRegistry serviceRegistry = new DicomServiceRegistry();
        serviceRegistry.addDicomService(new BasicCEchoSCP());
        serviceRegistry.addDicomService(new AbstractDicomService(UID.InstanceAvailabilityNotificationSOPClass) {
            @Override
            protected void onDimseRQ(Association as, PresentationContext pc, Dimse dimse,
                                     Attributes cmd, Attributes data) throws IOException {
                if (dimse != Dimse.N_CREATE_RQ)
                    throw new DicomServiceException(Status.UnrecognizedOperation);
                IANSCP.this.process(data);
                as.tryWriteDimseRSP(pc, Commands.mkNCreateRSP(cmd, Status.Success));
            }
        });
        return serviceRegistry;
    }

    private void process(Attributes ian) {
        Attributes refMPPS = ian.getNestedDataset(Tag.ReferencedPerformedProcedureStepSequence);
        System.out.printf("IAN for STUDY[%s]%n", ian.getString(Tag.StudyInstanceUID));
        if (refMPPS != null) {
            System.out.printf("\t%s[%s]:%n",
                    UID.nameOf(refMPPS.getString(Tag.ReferencedSOPClassUID)),
                    refMPPS.getString(Tag.ReferencedSOPInstanceUID));
        }
        for (Attributes refSeries : ian.getSequence(Tag.ReferencedSeriesSequence)) {
            System.out.printf("\tSERIES[%s]%n", refSeries.getString(Tag.SeriesInstanceUID));
            for (Attributes refSOP : refSeries.getSequence(Tag.ReferencedSOPSequence)) {
                System.out.printf("\t\t%s[%s] - %s%n",
                        UID.nameOf(refSOP.getString(Tag.ReferencedSOPClassUID)),
                        refSOP.getString(Tag.ReferencedSOPInstanceUID),
                        refSOP.getString(Tag.InstanceAvailability));
            }
        }
    }

    public static void main(String[] args) throws Exception {
        CLI cli = CLI.parse(args);
        ExecutorService executor = Executors.newCachedThreadPool();
        ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        IANSCP scp = new IANSCP(cli.calledAET, cli.bindAddress, cli.port);
        scp.setExecutor(executor);
        scp.setScheduledExecutor(scheduledExecutor);
        scp.start();
    }

    private static class CLI {
        final String calledAET;
        final String bindAddress;
        final int port;

        CLI(String[] args) {
            calledAET = args[0];
            bindAddress = args[1];
            port = Integer.parseInt(args[2]);
        }

        static CLI parse(String[] args) {
            try {
                return new CLI(args);
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                System.out.println("Usage: ian-scp <called-aet> <bind-address> <port>");
                System.exit(-1);
                return null;
            }
        }
    }
}
