package edu.must.campuslostfound;

import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;
import static edu.must.campuslostfound.NoticeModels.*;

@Component
class PhotoProcessor {
    static final long MAX_BYTES=5L*1024*1024;
    List<PreparedPhoto> prepare(List<MultipartFile> files) {
        if (files.size()>3) throw invalid("Use no more than 3 photos.");
        List<PreparedPhoto> result=new ArrayList<>();
        for (var file:files) {
            if (file.isEmpty() || file.getSize()>MAX_BYTES) throw invalid("Each photo must be a readable JPEG/PNG of at most 5 MB.");
            try {
                byte[] bytes=file.getBytes();
                boolean png=bytes.length>=24 && bytes[0]==(byte)137 && bytes[1]==80 && bytes[2]==78 && bytes[3]==71;
                boolean jpg=bytes.length>=4 && bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[bytes.length-2]==(byte)255 && bytes[bytes.length-1]==(byte)217;
                if (!png && !jpg) throw invalid("Only readable JPEG and PNG photos are supported.");
                try (var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
                    var readers=ImageIO.getImageReaders(input);
                    if (!readers.hasNext()) throw invalid("The photo could not be decoded.");
                    var reader=readers.next();
                    try {
                        reader.setInput(input,true,true);
                        String format=reader.getFormatName();
                        if (!(png && format.equalsIgnoreCase("png")) && !(jpg && (format.equalsIgnoreCase("jpeg")||format.equalsIgnoreCase("jpg")))) throw invalid("The photo content is not JPEG/PNG.");
                        int w=reader.getWidth(0),h=reader.getHeight(0);
                        if(w<1||h<1||w>20000||h>20000||(long)w*h>100_000_000) throw invalid("Photo dimensions are too large. Resize this photo before uploading.");
                        var warning=new AtomicBoolean(); reader.addIIOReadWarningListener((s,m)->warning.set(true));
                        var params=reader.getDefaultReadParam();
                        int sample=Math.max(1,(int)Math.ceil(Math.max(w,h)/2048.0));
                        params.setSourceSubsampling(sample,sample,0,0);
                        BufferedImage image=reader.read(0,params);
                        if(image==null||warning.get()) throw invalid("The photo is damaged. Choose another file.");
                        // Re-encode pixels without source metadata, filenames or EXIF/GPS information.
                        var output=new ByteArrayOutputStream();
                        if(!ImageIO.write(image,png?"png":"jpeg",output)) throw invalid("The photo could not be saved.");
                        result.add(new PreparedPhoto(UUID.randomUUID().toString(),png?"image/png":"image/jpeg",output.toByteArray()));
                    } finally {reader.dispose();}
                }
            } catch(ApiProblem e) {throw e;} catch(Exception e) {throw invalid("The photo is damaged or unsupported. Choose another file.");}
        }
        return result;
    }
    private ApiProblem invalid(String message) {return ApiProblem.invalid(Map.of("photos",message));}
}
