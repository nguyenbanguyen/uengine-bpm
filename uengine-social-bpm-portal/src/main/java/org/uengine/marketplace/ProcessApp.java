package org.uengine.marketplace;

import org.metaworks.annotation.Face;
import org.metaworks.annotation.Hidden;
import org.metaworks.annotation.Resource;
import org.metaworks.component.SelectBox;
import org.metaworks.dwr.MetaworksRemoteService;
import org.metaworks.website.MetaworksFile;
import org.metaworks.widget.Label;
import org.oce.garuda.multitenancy.Operation;
import org.oce.garuda.multitenancy.TenantContext;
import org.springframework.context.annotation.Scope;
import org.springframework.stereotype.Component;
import org.uengine.codi.mw3.marketplace.App;
import org.uengine.codi.mw3.marketplace.AppTypePanel;
import org.uengine.codi.mw3.marketplace.category.Category;
import org.uengine.codi.mw3.marketplace.category.ICategory;
import org.uengine.codi.mw3.model.IProcessMap;
import org.uengine.codi.mw3.model.ProcessMap;
import org.uengine.modeling.resource.*;
import org.uengine.social.SocialBPMProcessDefinitionSelector;
import org.uengine.util.UEngineUtil;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

import static org.metaworks.dwr.MetaworksRemoteService.autowire;
import static org.metaworks.dwr.MetaworksRemoteService.getComponent;

/**
 * Created by jjy on 2015. 11. 5..
 */

@Component
@Scope("prototype")
public class ProcessApp extends App{
    public ProcessApp() throws Exception {
        super();

    }

    @Override
    public void load() throws Exception {
        SelectBox categories = new SelectBox();

        ICategory category = Category.loadRootCategory();
        if (category.size() > 0) {
            while (category.next()) {

                String categoryId = Integer.toString(category.getCategoryId());
                String categoryName = category.getCategoryName();

                categories.add(categoryName, categoryId);
            }
        }

        this.setCategories(categories);



        if( this.getLogoFile() == null ){
            this.setLogoFile(new MetaworksFile());
        }
    }


    @Override
    @Hidden
    public AppTypePanel getAppTypePanel() {
        return super.getAppTypePanel();
    }

    @Override
    @Face(faceClass=SocialBPMProcessDefinitionSelector.class)
    public String getProjectId() {
        return super.getProjectId();
    }


    final int BUFFER = 2048;


    @Override
    public Object save() throws Exception {

        boolean resourceIsInDefaultFolder = (getProjectId().substring(getProjectId().indexOf("/")+1).indexOf("/") == -1);

//        if(defaultFolder){
//            throw new Exception("Definition must be in a package (folder)");
//        }

        byte data[] = new byte[BUFFER];

        List<IResource> resourceList;

        if(!resourceIsInDefaultFolder) {
            ContainerResource containerResource = new ContainerResource();

            autowire(containerResource);

            containerResource.setPath(UEngineUtil.getFilePath(getProjectId()));
            resourceList = containerResource.list();
            setUrl(containerResource.getPath());
        }else{
            resourceList = new ArrayList<IResource>();

            DefaultResource onlyOneResource = new DefaultResource("codi/" + getProjectId());
            resourceList.add(onlyOneResource);

            setUrl(UEngineUtil.getFilePath(getProjectId()));
        }


        Object returnVal = super.save();


        final ResourceManager resourceManagerForMarketplace = getComponent(ResourceManager.class, "resourceManagerForMarketplace");
        final ResourceManager resourceManager = getComponent(ResourceManager.class);


        ZipOutputStream out = (ZipOutputStream) TenantContext.nonTenantSpecificOperation(new Operation() {
            @Override
            public Object run() {

                DefaultResource zipFile = new DefaultResource();
                zipFile.setPath(getAppId() + ".processapp");

                try {
                    ZipOutputStream out = new ZipOutputStream(new
                            BufferedOutputStream(resourceManagerForMarketplace.getStorage().getOutputStream(zipFile)));
                    return out;
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }

            }
        });

        InputStream origin = null;

        for(IResource resource : resourceList){

            if(resource instanceof DefaultResource && !(resource instanceof IContainer)) {
                origin = resourceManager.getStorage().getInputStream(resource);

                try {
                    ZipEntry entry = new ZipEntry(UEngineUtil.getFileName(resource.getPath()));
                    out.putNextEntry(entry);

                    int count;
                    while ((count = origin.read(data, 0,
                            BUFFER)) != -1) {
                        out.write(data, 0, count);
                    }
                } catch (Exception e) {
                    throw e;
                } finally {
                    if (origin != null)
                        origin.close();
                }
            }
        }

        out.close();


        return returnVal;
    }


    @Override
    public Object[] addApp() throws Exception {

       BufferedOutputStream dest = null;


        final ResourceManager resourceManagerForMarketplace = getComponent(ResourceManager.class, "resourceManagerForMarketplace");
        final ResourceManager resourceManager = MetaworksRemoteService.getComponent(ResourceManager.class);

        ZipInputStream zis = (ZipInputStream) TenantContext.nonTenantSpecificOperation(new Operation(){

            @Override
            public Object run() {


                DefaultResource processAppResource = new DefaultResource();
                processAppResource.setPath(getAppId() + ".processapp");

                MetaworksRemoteService.autowire(processAppResource);

                try {
                    return new
                            ZipInputStream(new BufferedInputStream(resourceManagerForMarketplace.getStorage().getInputStream(processAppResource)));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        });


        ZipEntry entry;
        while((entry = zis.getNextEntry()) != null) {
            System.out.println("Extracting: " +entry);

            int count;
            byte data[] = new byte[BUFFER];
            // write the files to the disk

            DefaultResource defaultResource = new DefaultResource();
            defaultResource.setPath("codi/" + getUrl() + "/" + entry.getName());

            if(resourceManager.getStorage().exists(defaultResource)){
                throw new Exception("There's already installed resource: " + defaultResource.getPath() + ". You must delete the resources before installing this resource.");
            }

            OutputStream baos = new ByteArrayOutputStream();
            //fos = resourceManager.getStorage().getOutputStream(defaultResource);

            dest = new
                    BufferedOutputStream(baos, BUFFER);
            while ((count = zis.read(data, 0, BUFFER))
                    != -1) {
                dest.write(data, 0, count);
            }
            dest.flush();
            dest.close();

            resourceManager.save(defaultResource, Serializer.deserialize(baos.toString()));

            deployApp(defaultResource);
        }

        return new Object[]{new Label("<center><h2>Successfully Installed.</h2></center>")};//super.addApp();

       // return super.addApp();
    }

    protected void deployApp(DefaultResource defaultResource) {
        String type = defaultResource.getType();



        if("process".equals(type) || "method".equals(type)){

            {//dup check first
                IProcessMap processMap = new ProcessMap();
                processMap.setMapId(TenantContext.getThreadLocalInstance().getTenantId() + "." + defaultResource.getName());
                processMap.setDefId(defaultResource.getPath());
                processMap.setName(defaultResource.getName());
                processMap.setComCode(TenantContext.getThreadLocalInstance().getTenantId());

                try {
                    processMap.select();

                    if(processMap.next()){
                        return; //if exists, skip deploying.
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }


            IProcessMap processMap = new ProcessMap();
            processMap.setMapId(TenantContext.getThreadLocalInstance().getTenantId() + "." + defaultResource.getName());
            processMap.setDefId(defaultResource.getPath());
            processMap.setName(defaultResource.getName());
            processMap.setComCode(TenantContext.getThreadLocalInstance().getTenantId());
            //processMap.setNo(i);
            //processMap.setIconFile(new MetaworksFile());
            //processMap.getIconFile().setUploadedPath(upLoadedPath[i]);
            try {
                processMap.createMe();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
    }

}
