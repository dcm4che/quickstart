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

package org.dcm4che3.quickstart.cechoscu;

import org.dcm4che3.data.UID;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;

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
public class CEchoSCU {

    private Device device;
    private ApplicationEntity ae;
    private Connection conn;

    public CEchoSCU(String callingAET) {
        device = new Device("c-echo-scu");
        ae = new ApplicationEntity(callingAET);
        conn = new Connection();
        device.addApplicationEntity(ae);
        device.addConnection(conn);
        ae.addConnection(conn);
    }

    public void setExecutor(Executor executor) {
        device.setExecutor(executor);
    }

    public void setScheduledExecutor(ScheduledExecutorService executor) {
        device.setScheduledExecutor(executor);
    }

    public void echo(String calledAET, String hostName, int port)
            throws IOException, InterruptedException, GeneralSecurityException, IncompatibleConnectionException {
        Association as = ae.connect(mkConnection(hostName, port), mkAARQ(calledAET));
        as.cecho();
        as.release();
    }

    private AAssociateRQ mkAARQ(String calledAET) {
        AAssociateRQ aarq = new AAssociateRQ();
        aarq.setCallingAET(ae.getAETitle()); // if not explicitly set, will be set in ae.connect().
        aarq.setCalledAET(calledAET);
        aarq.addPresentationContext(new PresentationContext(1,
                UID.VerificationSOPClass, UID.ImplicitVRLittleEndian));
        return aarq;
    }

    private Connection mkConnection(String hostName, int port) {
        return new Connection(null, hostName, port);
    }

    public static void main(String[] args) throws Exception {
        CLI cli = CLI.parse(args);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        try {
            CEchoSCU scu = new CEchoSCU(cli.callingAET);
            scu.setExecutor(executor);
            scu.setScheduledExecutor(scheduledExecutor);
            scu.echo(cli.calledAET, cli.hostname, cli.port);
        } finally {
            executor.shutdown();
            scheduledExecutor.shutdown();
        }
    }

    private static class CLI {
        final String callingAET;
        final String calledAET;
        final String hostname;
        final int port;

        CLI(String[] args) {
            callingAET = args[0];
            calledAET = args[1];
            hostname = args[2];
            port = Integer.parseInt(args[3]);
        }

        static CLI parse(String[] args) {
            try {
                return new CLI(args);
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                System.out.println("Usage: c-echo-scu <calling-aet> <called-aet> <host> <port>");
                System.exit(-1);
                return null;
            }
        }
    }
}
