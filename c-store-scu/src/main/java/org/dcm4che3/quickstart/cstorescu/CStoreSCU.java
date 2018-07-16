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

package org.dcm4che3.quickstart.cstorescu;

import org.dcm4che3.data.Attributes;
import org.dcm4che3.data.Tag;
import org.dcm4che3.data.VR;
import org.dcm4che3.io.DicomInputStream;
import org.dcm4che3.net.*;
import org.dcm4che3.net.pdu.AAssociateRQ;
import org.dcm4che3.net.pdu.PresentationContext;
import org.dcm4che3.util.StreamUtils;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.stream.Collectors;

/**
 * @author Gunter Zeilinger <gunterze@gmail.com>
 * @since Jul 2018
 */
public class CStoreSCU {
    private Device device;
    private ApplicationEntity ae;
    private Connection conn;
    public CStoreSCU(String callingAET) {
        device = new Device("c-store-scu");
        ae = new ApplicationEntity(callingAET);
        conn = new Connection();
        // enable Asynchronous Operations
        conn.setMaxOpsInvoked(0);
        conn.setMaxOpsPerformed(0);
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

    public void store(String calledAET, String hostName, int port, List<File> files)
            throws IOException, InterruptedException, GeneralSecurityException, IncompatibleConnectionException {
        Map<Boolean, List<DicomFile>> parsedFiles = files.stream().map(DicomFile::new)
                .collect(Collectors.partitioningBy(DicomFile::failed));
        parsedFiles.get(true).forEach(DicomFile::logError);
        List<DicomFile> dicomFiles = parsedFiles.get(false);
        if (dicomFiles.isEmpty()) {
            return;
        }
        Association as = ae.connect(mkConnection(hostName, port), mkAARQ(calledAET, dicomFiles));
        for (DicomFile dicomFile : dicomFiles) {
            try {
                as.cstore(dicomFile.cuid, dicomFile.iuid, Priority.NORMAL, dicomFile, dicomFile.tsuid,
                        new DimseRSPHandler(as.nextMessageID()));
            } catch (IOException e) {
                System.out.printf("Failed to send %s to %s - %s%n", dicomFile.file, calledAET, e);
            }
        }
        as.waitForOutstandingRSP();
        as.release();
    }

    private AAssociateRQ mkAARQ(String calledAET, List<DicomFile> dicomFiles) {
        AAssociateRQ aarq = new AAssociateRQ();
        aarq.setCallingAET(ae.getAETitle()); // optional: will be set in ae.connect() if not explicitly set.
        aarq.setCalledAET(calledAET);
        dicomFiles.stream().filter(e -> !aarq.containsPresentationContextFor(e.cuid, e.tsuid))
                .forEach(e -> aarq.addPresentationContext(
                        new PresentationContext(
                                aarq.getNumberOfPresentationContexts() * 2 + 1,
                                e.cuid,
                                e.tsuid)));
        return aarq;
    }

    private Connection mkConnection(String hostName, int port) {
        return new Connection(null, hostName, port);
    }

    private static class DicomFile implements DataWriter {
        final File file;
        String iuid;
        String cuid;
        String tsuid;
        long fmiLength;
        IOException ex;

        DicomFile(File file) {
            this.file = file;
            try (DicomInputStream dis = new DicomInputStream(file)) {
                Attributes fmi = dis.readFileMetaInformation();
                if (fmi != null) { // DICOM Part 10 File
                    fmiLength = dis.getPosition();
                    cuid = fmi.getString(Tag.MediaStorageSOPClassUID);
                    iuid = fmi.getString(Tag.MediaStorageSOPInstanceUID);
                    tsuid = fmi.getString(Tag.TransferSyntaxUID);
                } else { // bare DICOM Data set without File Meta Information
                    Attributes attrs = dis.readDataset(-1, Tag.SOPInstanceUID);
                    attrs.setBytes(Tag.SOPInstanceUID, VR.UI, dis.readValue());
                    cuid = attrs.getString(Tag.SOPClassUID);
                    iuid = attrs.getString(Tag.SOPInstanceUID);
                    tsuid = dis.getTransferSyntax();
                }
            } catch (IOException e) {
                ex = e;
            }
        }

        boolean failed() {
            return ex != null;
        }

        void logError() {
            System.out.printf("Failed to parse %s - %s%n", file, ex);
        }

        @Override
        public void writeTo(PDVOutputStream out, String tsuid) throws IOException {
            try (FileInputStream in = new FileInputStream(file)) {
                in.skip(fmiLength);
                StreamUtils.copy(in, out);
            }
        }
    }

    public static void main(String[] args) throws Exception {
        CLI cli = CLI.parse(args);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        ScheduledExecutorService scheduledExecutor = Executors.newSingleThreadScheduledExecutor();
        try {
            CStoreSCU scu = new CStoreSCU(cli.callingAET);
            scu.setExecutor(executor);
            scu.setScheduledExecutor(scheduledExecutor);
            scu.store(cli.calledAET, cli.hostname, cli.port, cli.files);
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
        final List<File> files = new ArrayList<>();

        CLI(String[] args) {
            callingAET = args[0];
            calledAET = args[1];
            hostname = args[2];
            port = Integer.parseInt(args[3]);
            for (int i = 4; i < args.length; i++) {
                add(new File(args[i]));
            }
        }

        private void add(File f) {
            if (f.isDirectory()) {
                for (File file : f.listFiles()) {
                    add(file);
                }
            } else {
                files.add(f);
            }
        }

        static CLI parse(String[] args) {
            try {
                return new CLI(args);
            } catch (IndexOutOfBoundsException | NumberFormatException e) {
                System.out.println("Usage: c-store-scu <calling-aet> <called-aet> <host> <port> <directory|file>...");
                System.exit(-1);
                return null;
            }
        }
    }
}
