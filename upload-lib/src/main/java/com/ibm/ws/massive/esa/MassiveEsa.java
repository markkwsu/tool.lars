/*******************************************************************************
 * Copyright (c) 2015 IBM Corp.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/

package com.ibm.ws.massive.esa;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitOption;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

import org.apache.aries.util.VersionRange;
import org.apache.aries.util.manifest.ManifestProcessor;
import org.osgi.framework.Version;

import com.ibm.ws.massive.esa.internal.EsaManifest;
import com.ibm.ws.massive.esa.internal.ManifestHeaderProcessor;
import com.ibm.ws.massive.esa.internal.ManifestHeaderProcessor.GenericMetadata;
import com.ibm.ws.massive.upload.RepositoryArchiveEntryNotFoundException;
import com.ibm.ws.massive.upload.RepositoryArchiveIOException;
import com.ibm.ws.massive.upload.RepositoryArchiveInvalidEntryException;
import com.ibm.ws.massive.upload.RepositoryUploader;
import com.ibm.ws.massive.upload.internal.MassiveUploader;
import com.ibm.ws.repository.common.enums.AttachmentType;
import com.ibm.ws.repository.common.enums.DisplayPolicy;
import com.ibm.ws.repository.common.enums.InstallPolicy;
import com.ibm.ws.repository.common.enums.LicenseType;
import com.ibm.ws.repository.common.enums.Visibility;
import com.ibm.ws.repository.connections.RepositoryConnection;
import com.ibm.ws.repository.exceptions.RepositoryException;
import com.ibm.ws.repository.exceptions.RepositoryResourceCreationException;
import com.ibm.ws.repository.exceptions.RepositoryResourceUpdateException;
import com.ibm.ws.repository.resources.EsaResource;
import com.ibm.ws.repository.resources.internal.AppliesToProcessor;
import com.ibm.ws.repository.resources.writeable.AttachmentResourceWritable;
import com.ibm.ws.repository.resources.writeable.EsaResourceWritable;
import com.ibm.ws.repository.resources.writeable.WritableResourceFactory;
import com.ibm.ws.repository.strategies.writeable.UploadStrategy;

/**
 * <p>
 * This class contains methods for working with ESAs inside MaaSive.
 * </p>
 */
public class MassiveEsa extends MassiveUploader implements RepositoryUploader<EsaResourceWritable> {

    private static final String REQUIRE_CAPABILITY_HEADER_NAME = "Require-Capability";
    private static final VersionRange JAVA_11_RANGE = VersionRange.parseVersionRange("[1.2,11]");
    private static final VersionRange JAVA_10_RANGE = VersionRange.parseVersionRange("[1.2,10]");
    private static final VersionRange JAVA_9_RANGE = VersionRange.parseVersionRange("[1.2,9]");
    private static final VersionRange JAVA_8_RANGE = VersionRange.parseVersionRange("[1.2,1.8]");
    private static final VersionRange JAVA_7_RANGE = new VersionRange("[1.2,1.7]");
    private static final VersionRange JAVA_6_RANGE = new VersionRange("[1.2,1.6]");
    private static final String OSGI_EE_NAMESPACE_ID = "osgi.ee";
    private static final String JAVA_FILTER_KEY = "JavaSE";
    private static final String VERSION_FILTER_KEY = "version";

    /**
     * Construct a new instance and load all of the existing features inside MaaSive.
     *
     * @param userId The userId to use to connect to Massive
     * @param password The password to use to connect to Massive
     * @param apiKey The API key to use to connect to Massive
     * @throws RepositoryException
     */
    public MassiveEsa(RepositoryConnection repoConnection) throws RepositoryException {
        super(repoConnection);
    }

    /**
     * This method will add a collection of ESAs into MaaSive
     *
     * @param esas The ESAs to add
     * @return the new {@link EsaResource}s added to massive (will not included any resources that were
     *         modified as a result of this operation)
     * @throws ZipException
     * @throws RepositoryResourceCreationException
     * @throws RepositoryResourceUpdateException
     */
    public Collection<EsaResource> addEsasToMassive(Collection<File> esas, UploadStrategy strategy) throws RepositoryException {
        Collection<EsaResource> resources = new HashSet<EsaResource>();
        for (File esa : esas) {
            EsaResource resource = uploadFile(esa, strategy, null);
            resources.add(resource);
        }

        return resources;
    }

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.ws.massive.upload.RepositoryUploader#canUploadFile(java.io.File)
     */
    @Override
    public boolean canUploadFile(File assetFile) {
        return assetFile.getName().endsWith(".esa");
    }

    /*
     * (non-Javadoc)
     *
     * @see com.ibm.ws.massive.upload.RepositoryUploader#uploadFile(java.io.File,
     * com.ibm.ws.massive.resources.UploadStrategy)
     */
    @Override
    @SuppressWarnings("deprecation")
    public EsaResourceWritable uploadFile(File esa, UploadStrategy strategy, String contentUrl) throws RepositoryException {

        ArtifactMetadata artifactMetadata = explodeArtifact(esa);

        // Read the meta data from the esa
        EsaManifest feature;
        try {
            feature = EsaManifest.constructInstance(esa);
        } catch (IOException e) {
            throw new RepositoryArchiveIOException(e.getMessage(), esa, e);
        }

        /*
         * First see if we already have this feature in MaaSive, note this means we can only have one
         * version of the asset in MaaSive at a time
         */
        EsaResourceWritable resource = WritableResourceFactory.createEsa(repoConnection);
        String symbolicName = feature.getSymbolicName();
        String version = feature.getVersion().toString();

        // Massive assets are always English, find the best name
        String subsystemName = feature.getHeader("Subsystem-Name",
                                                 Locale.ENGLISH);
        String shortName = feature.getIbmShortName();
        String metadataName = artifactMetadata != null ? artifactMetadata.getName() : null;
        final String name;

        /*
         * We want to be able to override the name in the built ESA with a value supplied in the metadata so
         * use this in preference of what is in the ESA so that we can correct any typos post-GM
         */
        if (metadataName != null && !metadataName.isEmpty()) {
            name = metadataName;
        } else if (subsystemName != null && !subsystemName.isEmpty()) {
            name = subsystemName;
        } else if (shortName != null && !shortName.isEmpty()) {
            name = shortName;
        } else {
            // symbolic name is always set
            name = symbolicName;
        }

        resource.setName(name);
        String shortDescription = null;
        if (artifactMetadata != null) {
            shortDescription = artifactMetadata.getShortDescription();
            resource.setDescription(artifactMetadata.getLongDescription());
        }
        if (shortDescription == null) {
            shortDescription = feature.getHeader("Subsystem-Description", Locale.ENGLISH);
        }
        resource.setShortDescription(shortDescription);
        resource.setVersion(version);

        //Add icon files
        processIcons(esa, feature, resource);

        String provider = feature.getHeader("Subsystem-Vendor");
        if (provider != null && !provider.isEmpty()) {
            resource.setProviderName(provider);
        }

        // Add custom attributes for WLP
        resource.setProvideFeature(symbolicName);
        resource.setAppliesTo(feature.getHeader("IBM-AppliesTo"));
        Visibility visibility = feature.getVisibility();
        resource.setVisibility(visibility);

        /*
         * Two things affect the display policy - the visibility and the install policy. If a private auto
         * feature is set to manual install we need to make it visible so people know that it exists and can
         * be installed
         */
        DisplayPolicy displayPolicy;
        DisplayPolicy webDisplayPolicy;

        if (visibility == Visibility.PUBLIC) {
            displayPolicy = DisplayPolicy.VISIBLE;
            webDisplayPolicy = DisplayPolicy.VISIBLE;
        } else {
            displayPolicy = DisplayPolicy.HIDDEN;
            webDisplayPolicy = DisplayPolicy.HIDDEN;
        }

        if (feature.isAutoFeature()) {
            resource.setProvisionCapability(feature.getHeader("IBM-Provision-Capability"));
            String IBMInstallPolicy = feature.getHeader("IBM-Install-Policy");

            // Default InstallPolicy is set to MANUAL
            InstallPolicy installPolicy;
            if (IBMInstallPolicy != null && ("when-satisfied".equals(IBMInstallPolicy))) {
                installPolicy = InstallPolicy.WHEN_SATISFIED;
            } else {
                installPolicy = InstallPolicy.MANUAL;
                // As discussed above set the display policy to visible for any manual auto features
                displayPolicy = DisplayPolicy.VISIBLE;
                webDisplayPolicy = DisplayPolicy.VISIBLE;
            }
            resource.setInstallPolicy(installPolicy);
        }

        // if we are dealing with a beta feature hide it otherwise apply the
        // display policies from above
        if (isBeta(resource.getAppliesTo())) {
            resource.setWebDisplayPolicy(DisplayPolicy.HIDDEN);
        } else {
            resource.setWebDisplayPolicy(webDisplayPolicy);
        }

        // Always set displayPolicy
        resource.setDisplayPolicy(displayPolicy);

        // handle required iFixes
        String requiredFixes = feature.getHeader("IBM-Require-Fix");
        if (requiredFixes != null && !requiredFixes.isEmpty()) {
            String[] fixes = requiredFixes.split(",");
            for (String fix : fixes) {
                fix = fix.trim();
                if (!fix.isEmpty()) {
                    resource.addRequireFix(fix);
                }
            }
        }

        resource.setShortName(shortName);

        // Calculate which features this relies on
        Map<String, List<String>> requiredFeaturesWithTolerates = feature.getRequiredFeatureWithTolerates();
        if (requiredFeaturesWithTolerates != null) {
            for (Map.Entry<String, List<String>> entry : requiredFeaturesWithTolerates.entrySet()) {
                resource.addRequireFeatureWithTolerates(entry.getKey(), entry.getValue().size() == 0 ? null : entry.getValue());
            }
        }

        // Old version which does not collect and store tolerates info
        // Calculate which features this relies on
//        for (String requiredFeature : feature.getRequiredFeatures()) {
//            resource.addRequireFeature(requiredFeature);
//        }

        // feature.supersededBy is a comma-separated list of shortNames. Add
        // each of the elements to either supersededBy or supersededByOptional.
        String supersededBy = feature.getSupersededBy();
        if (supersededBy != null && !supersededBy.trim().isEmpty()) {
            String[] supersededByArray = supersededBy.split(",");
            for (String f : supersededByArray) {
                // If one of the elements is surrounded by [square brackets] then we
                // strip the brackets off and treat it as optional
                if (f.startsWith("[")) {
                    f = f.substring(1, f.length() - 1);
                    resource.addSupersededByOptional(f);
                } else {
                    resource.addSupersededBy(f);
                }
            }
        }

        setJavaRequirements(esa, resource);

        String attachmentName = symbolicName + ".esa";
        addContent(resource, esa, attachmentName, artifactMetadata, contentUrl);

        // Set the license type if we're using the feature terms agreement
        String subsystemLicense = feature.getHeader("Subsystem-License");
        if (subsystemLicense != null && subsystemLicense.equals("http://www.ibm.com/licenses/wlp-featureterms-v1")) {
            resource.setLicenseType(LicenseType.UNSPECIFIED);
        }

        if (artifactMetadata != null) {
            attachLicenseData(artifactMetadata, resource);
        }

        // Now look for LI, LA files inside the .esa
        try {
            processLAandLI(esa, resource, feature);
        } catch (IOException e) {
            throw new RepositoryArchiveIOException(e.getMessage(), esa, e);
        }
        resource.setLicenseId(feature.getHeader("Subsystem-License"));

        resource.setSingleton(Boolean.toString(feature.isSingleton()));

        resource.setIBMInstallTo(feature.getHeader("IBM-InstallTo"));

        // Publish to massive
        try {
            resource.uploadToMassive(strategy);
        } catch (RepositoryException re) {
            throw re;
        }

//        resource.dump(System.out);
        return resource;
    }

    protected static boolean isBeta(String appliesTo) {
        // Use the appliesTo string to determine whether a feature is a Beta or a regular feature.
        // Beta features are of the format:
        // "com.ibm.websphere.appserver; productVersion=2014.8.0.0; productInstallType=Archive",
        if (appliesTo == null) {
            return false;
        } else {
            String regex = ".*productVersion=" + AppliesToProcessor.BETA_REGEX;
            boolean matches = appliesTo.matches(regex);
            return matches;
        }
    }

    private void processIcons(File esa, EsaManifest feature, EsaResourceWritable resource) throws RepositoryException {
        //checking icon file
        int size = 0;
        String current = "";
        String sizeString = "";
        String iconName = "";
        String subsystemIcon = feature.getHeader("Subsystem-Icon");

        if (subsystemIcon != null) {
            subsystemIcon = subsystemIcon.replaceAll("\\s", "");

            StringTokenizer s = new StringTokenizer(subsystemIcon, ",");
            while (s.hasMoreTokens()) {
                current = s.nextToken();

                if (current.contains(";")) { //if the icon has an associated size
                    StringTokenizer t = new StringTokenizer(current, ";");
                    while (t.hasMoreTokens()) {
                        sizeString = t.nextToken();

                        if (sizeString.contains("size=")) {
                            String sizes[] = sizeString.split("size=");
                            size = Integer.parseInt(sizes[sizes.length - 1]);
                        } else {
                            iconName = sizeString;
                        }
                    }

                } else {
                    iconName = current;
                }

                File icon = this.extractFileFromArchive(esa.getAbsolutePath(), iconName).getExtractedFile();
                if (icon.exists()) {
                    AttachmentResourceWritable at = resource.addAttachment(icon, AttachmentType.THUMBNAIL);
                    if (size != 0) {
                        at.setImageDimensions(size, size);
                    }
                } else {
                    throw new RepositoryArchiveEntryNotFoundException("Icon does not exist", esa, iconName);
                }
            }
        }
    }

    /**
     * Look in the esa for bundles with particular java version requirements. Create an aggregate
     * requirement of the esa as a whole, and write the data into the supplied resource
     *
     * @param esa
     * @param resource
     * @throws RepositoryException If there are any IOExceptions reading the esa, or if the the bundles
     *             have conflicting Java version requirements.
     */
    private static void setJavaRequirements(File esa, EsaResourceWritable resource) throws RepositoryException {

        Map<String, String> bundleRequirements = new HashMap<String, String>();
        Path zipfile = esa.toPath();
        Map<String, VersionRange> matchingEnvs = new LinkedHashMap<String, VersionRange>();

        matchingEnvs.put("Java 6", JAVA_6_RANGE);
        matchingEnvs.put("Java 7", JAVA_7_RANGE);
        matchingEnvs.put("Java 8", JAVA_8_RANGE);
        matchingEnvs.put("Java 9", JAVA_9_RANGE);
        matchingEnvs.put("Java 10", JAVA_10_RANGE);
        matchingEnvs.put("Java 11", JAVA_11_RANGE);

        StringBuilder message = new StringBuilder();

        // Map of Path of an esa or jar, to its Require-Capability string
        Map<Path, String> requiresMap = new HashMap<Path, String>();

        // build a set of capabilities of each of manifests in the bundles and the subsystem
        // manifest in the feature

        // 20230604 Updated by Mark Su
        // Map<String, ?> env = java.util.Collections.emptyMap();
        // try (final FileSystem zipSystem = FileSystems.newFileSystem(zipfile, env)) {
        // 20230825 Updated by Mark Su
        // Change back to use JDK8
        try (final FileSystem zipSystem = FileSystems.newFileSystem(zipfile, null)) {
        // 20230604 End of update
            // get the paths of each bundle jar in the root directory of the esa
            Iterable<Path> roots = zipSystem.getRootDirectories();
            BundleFinder finder = new BundleFinder(zipSystem);
            for (Path root : roots) {
                // Bundles should be in the root of the zip, so depth is 1
                Files.walkFileTree(root, new HashSet<FileVisitOption>(), 1, finder);
            }

            // Go through each bundle jar in the root of the esa and add their require
            // capabilites to the map
            for (Path bundle : finder.bundles) {
                addBundleManifestRequireCapability(zipSystem, bundle, requiresMap);
            }

            // now add the require capabilities of the esa subsystem manifest
            addSubsystemManifestRequireCapability(esa, requiresMap);
        } catch (IOException e) {
            // Any IOException means that the version info isn't reliable, so only thing to do is ditch out.
            throw new RepositoryArchiveIOException(e.getMessage(), esa, e);
        }

        // Loop through the set of requires capabilities
        Set<Entry<Path, String>> entries = requiresMap.entrySet();
        for (Entry<Path, String> entry : entries) {
            Path path = entry.getKey();

            // Get the GenericMetadata
            List<GenericMetadata> requirementMetadata = ManifestHeaderProcessor.parseRequirementString(entry.getValue());
            GenericMetadata eeVersionMetadata = null;
            for (GenericMetadata metaData : requirementMetadata) {
                if (metaData.getNamespace().equals(OSGI_EE_NAMESPACE_ID)) {
                    eeVersionMetadata = metaData;
                    break;
                }
            }

            if (eeVersionMetadata == null) {
                // No version requirements, go to the next bundle
                continue;
            }

            Map<String, String> dirs = eeVersionMetadata.getDirectives();
            for (Entry<String, String> e : dirs.entrySet()) {

                if (!e.getKey().equals("filter")) {
                    continue;
                }

                Map<String, String> filter = null;
                filter = ManifestHeaderProcessor.parseFilter(e.getValue());

                // The interesting filter should contain osgi.ee=JavaSE and version=XX
                if (!(filter.containsKey(OSGI_EE_NAMESPACE_ID) && filter.get(OSGI_EE_NAMESPACE_ID).equals(JAVA_FILTER_KEY)
                      && filter.containsKey(VERSION_FILTER_KEY))) {
                    continue; // Uninteresting filter
                }

                // Store the raw filter to add to the resource later.
                bundleRequirements.put(path.getFileName().toString(), dirs.get(e.getValue()));

                VersionRange range = ManifestHeaderProcessor.parseVersionRange(filter.get(VERSION_FILTER_KEY));
                Iterator<Entry<String, VersionRange>> iterator = matchingEnvs.entrySet().iterator();
                while (iterator.hasNext()) {
                    Entry<String, VersionRange> capability = iterator.next();
                    VersionRange intersection = capability.getValue().intersect(range);
                    if (intersection == null) {
                        // Store what caused this env to be removed, for error message later
                        message.append("Manifest from " + path.getFileName() + " with range " + range + " caused env for "
                                       + capability.getKey() + " to be removed. ");
                        iterator.remove();
                    }
                }

                // Assume there is only one Java version filter, so stop looking
                break;
            }
        }
        if (matchingEnvs.size() == 0) {
            throw new RepositoryException("ESA " + resource.getName() +
                                          " is invalid as no Java execution environment matches all the bundle requirements: "
                                          + message);
        }

        ArrayList<String> rawRequirements = new ArrayList<String>();
        for (Entry<String, String> e : bundleRequirements.entrySet()) {
            rawRequirements.add(e.getKey() + ": " + e.getValue());
        }
        if (rawRequirements.size() == 0) {
            rawRequirements = null;
        }

        // The only thing that really matter is the minimum Java level required for this
        // esa, as later Java levels provide earlier envs. Hence for now, the max is
        // always set to null
        // Need to get the first entry in the matchingEnvs map (it is a linked and hence ordered map),
        // hence the silliness below.
        Version min = matchingEnvs.entrySet().iterator().next().getValue().getMaximumVersion();
        resource.setJavaSEVersionRequirements(min.toString(), null, rawRequirements);

    }

    @Override
    protected void checkRequiredProperties(ArtifactMetadata artifact) throws RepositoryArchiveInvalidEntryException {
        checkPropertySet(PROP_DESCRIPTION, artifact);
    }

    /**
     * Adds the Require-Capability Strings from a bundle jar to the Map of Require-Capabilities found
     *
     * @param zipSystem - the FileSystem mapping to the feature containing this bundle
     * @param bundle - the bundle within a zipped up feature
     * @param requiresMap - Map of Path to Require-Capability
     * @throws IOException
     */
    private static void addBundleManifestRequireCapability(FileSystem zipSystem,
                                                           Path bundle,
                                                           Map<Path, String> requiresMap) throws IOException {

        Path extractedJar = null;
        try {
            // Need to extract the bundles to read their manifest, can't find a way to do this in place.
            extractedJar = Files.createTempFile("unpackedBundle", ".jar");
            extractedJar.toFile().deleteOnExit();
            Files.copy(bundle, extractedJar, StandardCopyOption.REPLACE_EXISTING);

            Manifest bundleJarManifest = null;
            JarFile bundleJar = null;
            try {
                bundleJar = new JarFile(extractedJar.toFile());
                bundleJarManifest = bundleJar.getManifest();
            } finally {
                if (bundleJar != null) {
                    bundleJar.close();
                }
            }

            Attributes bundleManifestAttrs = bundleJarManifest.getMainAttributes();
            String requireCapabilityAttr = bundleManifestAttrs.getValue(REQUIRE_CAPABILITY_HEADER_NAME);
            if (requireCapabilityAttr != null) {
                requiresMap.put(bundle, requireCapabilityAttr);
            }

        } finally {
            if (extractedJar != null) {
                extractedJar.toFile().delete();
            }
        }
    }

    /**
     * Adds the Require-Capability Strings from a SUBSYSTEM.MF to the Map of Require-Capabilities found
     *
     * @param esa - the feature file containing the SUBSYSTEM.MF
     * @param requiresMap - Map of Path to Require-Capability
     * @throws IOException
     */
    private static void addSubsystemManifestRequireCapability(File esa,
                                                              Map<Path, String> requiresMap) throws IOException {
        String esaLocation = esa.getAbsolutePath();
        ZipFile zip = null;
        try {
            zip = new ZipFile(esaLocation);
            Enumeration<? extends ZipEntry> zipEntries = zip.entries();
            ZipEntry subsystemEntry = null;
            while (zipEntries.hasMoreElements()) {
                ZipEntry nextEntry = zipEntries.nextElement();
                if ("OSGI-INF/SUBSYSTEM.MF".equalsIgnoreCase(nextEntry.getName())) {
                    subsystemEntry = nextEntry;
                    break;
                }
            }
            if (subsystemEntry == null) {
                ;
            } else {
                Manifest m = ManifestProcessor.parseManifest(zip.getInputStream(subsystemEntry));
                Attributes manifestAttrs = m.getMainAttributes();
                String requireCapabilityAttr = manifestAttrs.getValue(REQUIRE_CAPABILITY_HEADER_NAME);
                requiresMap.put(esa.toPath(), requireCapabilityAttr);
            }
        } finally {
            if (zip != null) {
                zip.close();
            }
        }
    }

    /**
     * BundleFinder Used to find the bundle jars within a feature
     */
    static class BundleFinder extends SimpleFileVisitor<Path> {
        FileSystem _zipSystem;
        PathMatcher _bundleMatcher;
        ArrayList<Path> bundles = new ArrayList<Path>();

        private BundleFinder(FileSystem zipSystem) {
            super();
            _zipSystem = zipSystem;
            // Bundles should be jars in the root of the zip
            _bundleMatcher = _zipSystem.getPathMatcher("glob:/*.jar");
        }

        @Override
        public FileVisitResult visitFile(Path file, BasicFileAttributes attr) {
            if (_bundleMatcher.matches(file)) {
                bundles.add(file);
            }
            return FileVisitResult.CONTINUE;
        }
    }
}
