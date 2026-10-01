import org.verapdf.gf.foundry.VeraGreenfieldFoundryProvider;
import org.verapdf.pdfa.Foundries;
import org.verapdf.pdfa.flavours.PDFAFlavour;

import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

/** 디렉터리의 PDF 전부를 PDF/A-2b 프로파일로 검증한다(선언된 flavour가 아니라 2b를 강제). 실패 규칙 수를 출력하고, 하나라도 있으면 종료 코드 1. */
public class PdfaVerify {

    public static void main(String[] args) throws Exception {
        Path dir = Path.of(args[0]);
        List<Path> pdfs;
        try (Stream<Path> s = Files.list(dir)) {
            pdfs = s.filter(p -> p.toString().endsWith(".pdf")).sorted().toList();
        }
        if (pdfs.isEmpty()) {
            System.out.println("no PDF in " + dir);
            System.exit(1);
        }
        VeraGreenfieldFoundryProvider.initialise();
        int failed = 0;
        for (Path pdf : pdfs) {
            try (var in = new FileInputStream(pdf.toFile());
                 var parser = Foundries.defaultInstance().createParser(in);
                 var validator = Foundries.defaultInstance().createValidator(PDFAFlavour.PDFA_2_B, false)) {
                var result = validator.validate(parser);
                int checks = result.getFailedChecks().size();
                System.out.println(pdf.getFileName() + " flavour=" + PDFAFlavour.PDFA_2_B + " declared=" + parser.getFlavour()
                        + " compliant=" + result.isCompliant() + " failedChecks=" + checks);
                result.getFailedChecks().keySet().forEach(rule -> System.out.println("  fail " + rule.getClause() + " #" + rule.getTestNumber()));
                if (!result.isCompliant() || checks > 0) {
                    failed++;
                }
            }
        }
        System.out.println(pdfs.size() + " PDFs, " + failed + " non-compliant");
        System.exit(failed == 0 ? 0 : 1);
    }
}
