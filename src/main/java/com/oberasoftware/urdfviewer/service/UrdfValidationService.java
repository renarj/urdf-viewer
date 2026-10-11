package com.oberasoftware.urdfviewer.service;

import com.oberasoftware.urdfviewer.config.UrdfViewerProperties;
import com.oberasoftware.urdfviewer.model.UrdfJointInfo;
import com.oberasoftware.urdfviewer.model.UrdfValidationReport;
import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import java.io.BufferedReader;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

@Service
public class UrdfValidationService {
    private static final Set<String> MOVABLE_JOINT_TYPES = Set.of("revolute", "continuous", "prismatic");

    private final UrdfViewerProperties properties;

    public UrdfValidationService(UrdfViewerProperties properties) {
        this.properties = properties;
    }

    public UrdfValidationReport validate(Path sourceRoot) {
        Path root = sourceRoot.toAbsolutePath().normalize();
        List<Path> files = regularFiles(root);
        if (files.isEmpty()) {
            throw invalid("The model bundle is empty");
        }
        if (files.size() > properties.getMaxFiles()) {
            throw invalid("The model exceeds the " + properties.getMaxFiles() + " file limit");
        }

        long totalBytes = files.stream().mapToLong(this::fileSize).sum();
        if (totalBytes > properties.getMaxExtractedBytes()) {
            throw invalid("The extracted model exceeds the configured size limit");
        }
        files.forEach(path -> {
            if (Files.isSymbolicLink(path)) {
                throw invalid("Symbolic links are not allowed in a model bundle");
            }
            if (fileSize(path) > properties.getMaxFileBytes()) {
                throw invalid("File exceeds the per-file size limit: " + relative(root, path));
            }
        });

        List<Path> urdfFiles = files.stream()
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".urdf"))
                .toList();
        Path urdfFile = selectPrimaryUrdf(urdfFiles);
        Document document = parseUrdf(urdfFile);
        Element robot = document.getDocumentElement();
        if (!"robot".equals(robot.getTagName())) {
            throw invalid("The URDF root element must be <robot>");
        }
        String robotName = requiredAttribute(robot, "name", "The URDF robot must have a name");
        validateLinks(document);

        Map<String, String> packageRoots = new LinkedHashMap<>();
        List<String> meshPaths = new ArrayList<>();
        List<Element> meshes = visualMeshes(document);
        if (meshes.size() > properties.getMaxMeshes()) {
            throw invalid("The URDF exceeds the " + properties.getMaxMeshes() + " mesh reference limit");
        }
        for (Element mesh : meshes) {
            String filename = requiredAttribute(mesh, "filename", "Every URDF mesh must have a filename");
            Path resolved = resolveMesh(root, urdfFile, filename, packageRoots);
            String relative = relative(root, resolved);
            if (!relative.toLowerCase(Locale.ROOT).endsWith(".stl")) {
                throw invalid("Unsupported mesh format for " + filename + "; only STL is supported");
            }
            meshPaths.add(relative);
        }

        List<String> uniqueMeshes = new ArrayList<>(new LinkedHashSet<>(meshPaths));
        long stlBytes = uniqueMeshes.stream().mapToLong(meshPath -> fileSize(root.resolve(meshPath))).sum();
        if (stlBytes > properties.getMaxStlBytes()) {
            throw invalid("The model exceeds the configured aggregate STL size limit");
        }
        long triangleCount = 0;
        for (String meshPath : uniqueMeshes) {
            triangleCount += countStlTriangles(root.resolve(meshPath));
            if (triangleCount > properties.getMaxTriangles()) {
                throw invalid("The model exceeds the " + properties.getMaxTriangles() + " triangle limit");
            }
        }

        return new UrdfValidationReport(
                robotName,
                relative(root, urdfFile),
                files.size(),
                meshes.size(),
                totalBytes,
                triangleCount,
                List.copyOf(uniqueMeshes),
                readJoints(document),
                Map.copyOf(packageRoots));
    }

    private List<Element> visualMeshes(Document document) {
        List<Element> result = new ArrayList<>();
        NodeList visuals = document.getElementsByTagName("visual");
        for (int i = 0; i < visuals.getLength(); i++) {
            NodeList meshes = ((Element) visuals.item(i)).getElementsByTagName("mesh");
            for (int meshIndex = 0; meshIndex < meshes.getLength(); meshIndex++) {
                result.add((Element) meshes.item(meshIndex));
            }
        }
        return result;
    }

    private Path selectPrimaryUrdf(List<Path> urdfFiles) {
        if (urdfFiles.isEmpty()) {
            throw invalid("A model must contain at least one .urdf file");
        }
        if (urdfFiles.size() == 1) {
            return urdfFiles.getFirst();
        }

        List<Path> explicitlyNamed = urdfFiles.stream()
                .filter(this::hasPrimaryUrdfName)
                .toList();
        if (explicitlyNamed.size() == 1) {
            return explicitlyNamed.getFirst();
        }

        List<Path> nonAuxiliary = urdfFiles.stream()
                .filter(path -> !hasAuxiliaryUrdfName(path))
                .toList();
        if (nonAuxiliary.size() == 1) {
            return nonAuxiliary.getFirst();
        }

        throw invalid("A model bundle with multiple URDF files must identify one primary model; found "
                + urdfFiles.size() + " .urdf files and " + nonAuxiliary.size() + " possible primary files");
    }

    private boolean hasPrimaryUrdfName(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.equals("robot.urdf") || name.equals("model.urdf") || name.equals("main.urdf");
    }

    private boolean hasAuxiliaryUrdfName(Path path) {
        String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
        String stem = name.substring(0, name.length() - ".urdf".length());
        Set<String> auxiliaryTokens = Set.of("reference", "preview", "collision", "static", "backup");
        return Stream.of(stem.split("[^a-z0-9]+"))
                .anyMatch(auxiliaryTokens::contains);
    }

    private List<Path> regularFiles(Path root) {
        try (Stream<Path> paths = Files.walk(root)) {
            return paths
                    .filter(Files::isRegularFile)
                    .sorted(Comparator.comparing(path -> relative(root, path)))
                    .toList();
        } catch (IOException e) {
            throw invalid("Could not inspect the uploaded model", e);
        }
    }

    private Document parseUrdf(Path urdfFile) {
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
            factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setXIncludeAware(false);
            factory.setExpandEntityReferences(false);
            var builder = factory.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.ErrorHandler() {
                @Override
                public void warning(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void error(SAXParseException exception) throws SAXException {
                    throw exception;
                }

                @Override
                public void fatalError(SAXParseException exception) throws SAXException {
                    throw exception;
                }
            });
            return builder.parse(urdfFile.toFile());
        } catch (ParserConfigurationException | SAXException | IOException e) {
            throw invalid("The URDF XML is invalid or uses unsupported external declarations", e);
        }
    }

    private void validateLinks(Document document) {
        NodeList links = document.getElementsByTagName("link");
        if (links.getLength() == 0) {
            throw invalid("The URDF must contain at least one link");
        }
        Set<String> names = new LinkedHashSet<>();
        for (int i = 0; i < links.getLength(); i++) {
            String name = requiredAttribute((Element) links.item(i), "name", "Every URDF link must have a name");
            if (!names.add(name)) {
                throw invalid("Duplicate URDF link name: " + name);
            }
        }
    }

    private List<UrdfJointInfo> readJoints(Document document) {
        NodeList nodes = document.getElementsByTagName("joint");
        Set<String> names = new LinkedHashSet<>();
        List<UrdfJointInfo> joints = new ArrayList<>();
        for (int i = 0; i < nodes.getLength(); i++) {
            Element joint = (Element) nodes.item(i);
            String name = requiredAttribute(joint, "name", "Every URDF joint must have a name");
            String type = requiredAttribute(joint, "type", "Every URDF joint must have a type").toLowerCase(Locale.ROOT);
            if (!names.add(name)) {
                throw invalid("Duplicate URDF joint name: " + name);
            }
            if (!MOVABLE_JOINT_TYPES.contains(type)) {
                continue;
            }
            Element limit = firstChild(joint, "limit");
            Double lower = numericAttribute(limit, "lower");
            Double upper = numericAttribute(limit, "upper");
            if ("continuous".equals(type)) {
                lower = -Math.PI;
                upper = Math.PI;
            } else if (lower != null && upper != null && lower > upper) {
                throw invalid("Joint " + name + " has a lower limit greater than its upper limit");
            }
            joints.add(new UrdfJointInfo(name, type, lower, upper));
        }
        return List.copyOf(joints);
    }

    private Path resolveMesh(Path root, Path urdfFile, String reference, Map<String, String> packageRoots) {
        if (reference.isBlank() || reference.indexOf('\\') >= 0 || reference.indexOf('%') >= 0
                || reference.startsWith("/") || reference.matches("^[A-Za-z]:.*")
                || reference.contains("://") && !reference.startsWith("package://")) {
            throw invalid("Unsafe or unsupported mesh reference: " + reference);
        }

        if (reference.startsWith("package://")) {
            String packageReference = reference.substring("package://".length());
            int separator = packageReference.indexOf('/');
            if (separator <= 0 || separator == packageReference.length() - 1) {
                throw invalid("Invalid package mesh reference: " + reference);
            }
            String packageName = packageReference.substring(0, separator);
            String packagePath = packageReference.substring(separator + 1);
            String knownRoot = packageRoots.get(packageName);
            if (knownRoot != null) {
                return requireMesh(root, resolveBelow(root, root.resolve(knownRoot), packagePath), reference);
            }

            List<Path> candidateRoots = new ArrayList<>();
            candidateRoots.add(root.resolve(packageName));
            Path urdfParent = urdfFile.getParent();
            if (urdfParent != null && packageName.equals(urdfParent.getFileName().toString())) {
                candidateRoots.add(urdfParent);
            }
            candidateRoots.add(root);
            for (Path candidateRoot : candidateRoots) {
                Path candidate = resolveBelow(root, candidateRoot, packagePath);
                if (Files.isRegularFile(candidate)) {
                    packageRoots.put(packageName, relativeRoot(root, candidateRoot));
                    return candidate;
                }
            }
            throw invalid("Package mesh does not exist inside the bundle: " + reference);
        }

        Path urdfParent = urdfFile.getParent();
        return requireMesh(root, resolveBelow(root, urdfParent, reference), reference);
    }

    private Path resolveBelow(Path root, Path base, String child) {
        Path candidate;
        try {
            candidate = base.resolve(child).normalize().toAbsolutePath();
        } catch (RuntimeException e) {
            throw invalid("Invalid model asset path: " + child, e);
        }
        if (!candidate.startsWith(root)) {
            throw invalid("Model asset escapes the uploaded model root: " + child);
        }
        return candidate;
    }

    private Path requireMesh(Path root, Path candidate, String reference) {
        if (!candidate.startsWith(root) || !Files.isRegularFile(candidate) || Files.isSymbolicLink(candidate)) {
            throw invalid("Referenced mesh does not exist inside the bundle: " + reference);
        }
        return candidate;
    }

    private long countStlTriangles(Path path) {
        long size = fileSize(path);
        if (size >= 84) {
            try (var input = Files.newInputStream(path)) {
                byte[] header = input.readNBytes(84);
                long triangles = Integer.toUnsignedLong(ByteBuffer.wrap(header, 80, 4)
                        .order(ByteOrder.LITTLE_ENDIAN).getInt());
                long expected;
                try {
                    expected = Math.addExact(84L, Math.multiplyExact(50L, triangles));
                } catch (ArithmeticException e) {
                    throw invalid("Invalid binary STL triangle count: " + path.getFileName());
                }
                if (expected == size) {
                    return triangles;
                }
            } catch (IOException e) {
                throw invalid("Could not read STL mesh " + path.getFileName(), e);
            }
        }

        long facets = 0;
        try (BufferedReader reader = Files.newBufferedReader(path, StandardCharsets.US_ASCII)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (line.stripLeading().startsWith("facet normal")) {
                    facets++;
                    if (facets > properties.getMaxTriangles()) {
                        return facets;
                    }
                }
            }
        } catch (IOException e) {
            throw invalid("STL mesh is neither valid binary nor readable ASCII: " + path.getFileName(), e);
        }
        if (facets == 0) {
            throw invalid("STL mesh contains no triangles: " + path.getFileName());
        }
        return facets;
    }

    private Element firstChild(Element parent, String tagName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child instanceof Element element && tagName.equals(element.getTagName())) {
                return element;
            }
        }
        return null;
    }

    private Double numericAttribute(Element element, String name) {
        if (element == null || !element.hasAttribute(name) || element.getAttribute(name).isBlank()) {
            return null;
        }
        try {
            double value = Double.parseDouble(element.getAttribute(name));
            if (!Double.isFinite(value)) {
                throw new NumberFormatException("not finite");
            }
            return value;
        } catch (NumberFormatException e) {
            throw invalid("Invalid numeric URDF attribute " + name, e);
        }
    }

    private String requiredAttribute(Element element, String name, String message) {
        String value = element.getAttribute(name);
        if (value == null || value.isBlank()) {
            throw invalid(message);
        }
        return value.trim();
    }

    private long fileSize(Path path) {
        try {
            return Files.size(path);
        } catch (IOException e) {
            throw invalid("Could not inspect uploaded file " + path.getFileName(), e);
        }
    }

    private String relative(Path root, Path path) {
        return root.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    private String relativeRoot(Path root, Path packageRoot) {
        String relative = relative(root, packageRoot);
        return ".".equals(relative) ? "" : relative;
    }

    private InvalidUrdfModelException invalid(String message) {
        return new InvalidUrdfModelException(message);
    }

    private InvalidUrdfModelException invalid(String message, Throwable cause) {
        return new InvalidUrdfModelException(message, cause);
    }
}
