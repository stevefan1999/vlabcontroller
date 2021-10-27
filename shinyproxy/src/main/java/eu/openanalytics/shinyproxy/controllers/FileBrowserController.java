/**
 * ShinyProxy
 *
 * Copyright (C) 2016-2021 Open Analytics
 *
 * ===========================================================================
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Apache License as published by
 * The Apache Software Foundation, either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * Apache License for more details.
 *
 * You should have received a copy of the Apache License
 * along with this program.  If not, see <http://www.apache.org/licenses/>
 */

package eu.openanalytics.shinyproxy.controllers;

import eu.openanalytics.containerproxy.ContainerProxyException;
import eu.openanalytics.containerproxy.model.runtime.Proxy;
import eu.openanalytics.containerproxy.model.runtime.ProxyStatus;
import eu.openanalytics.containerproxy.model.spec.ContainerSpec;
import eu.openanalytics.containerproxy.model.spec.ProxySpec;
import eu.openanalytics.containerproxy.spec.FileBrowserProperties;
import eu.openanalytics.containerproxy.util.Retrying;
import eu.openanalytics.containerproxy.util.SessionHelper;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.ui.ModelMap;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.ResponseBody;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Controller
public class FileBrowserController extends BaseController{

    private final Logger log = LogManager.getLogger(FileBrowserController.class);

    @Autowired(required = false)
    private FileBrowserProperties fileBrowserProperties;

    @RequestMapping(value="/filebrowser/**")
    public String fileBrowser(ModelMap map, HttpServletRequest request){
        prepareMap(map, request);
        String id = "filebrowser";
        Proxy proxy = proxyService.findProxy(p -> p.getSpec().getId().equals(id) && userService.isOwner(p), false);
        awaitReady(proxy);
        map.put("appTitle", "File Browser");
        map.put("container", (proxy == null) ? "" : buildContainerPath(request));
        return "filebrowser";
    }

    @RequestMapping(value = "/filebrowser/**", method = RequestMethod.POST)
    @ResponseBody
    public Map<String,String> startFileBrowser(HttpServletRequest request){
        String id = "filebrowser";
        Proxy proxy = proxyService.findProxy(p -> p.getSpec().getId().equals(id) && userService.isOwner(p), false);
        if (proxy == null){
            if (fileBrowserProperties != null){
                ProxySpec spec = fileBrowserSpecTranslate(fileBrowserProperties);
                ProxySpec resolvedSpec = proxyService.resolveProxySpec(spec, null, null);
                try{
                    proxy = proxyService.startProxy(resolvedSpec, false);
                }catch (ContainerProxyException e){
                    String errorMessage = "Failed to start file browser";
                    log.error(errorMessage);
                    log.debug(e);
                    Map<String, String> response = new HashMap<>();
                    response.put("error_code", "404");
                    response.put("error_message", errorMessage);
                    return response;
                }
            }else{
                String errorMessage = "Missing file browser spec";
                log.error(errorMessage);
                Map<String, String> response = new HashMap<>();
                response.put("error_code", "404");
                response.put("error_message", errorMessage);
                return response;
            }
        }
        awaitReady(proxy);
        String containerPath = buildContainerPath(request);
        Map<String,String> response = new HashMap<>();
        response.put("containerPath", containerPath);
        return response;
    }

    private ProxySpec fileBrowserSpecTranslate(FileBrowserProperties fbp){
        ProxySpec spec = new ProxySpec();
        spec.setId("filebrowser");
        spec.setDisplayName("File Browser");
        ContainerSpec cSpec = new ContainerSpec();
        if (fbp.getKubernetesPodPatches() != null) {
            try {
                spec.setKubernetesPodPatches(fbp.getKubernetesPodPatches());
            } catch (Exception e) {
                throw new IllegalArgumentException("Configuration error: file browser has invalid kubernetes-pod-patches");
            }
        }
        spec.setKubernetesAdditionalManifests(fbp.getKubernetesAdditionalManifests());
        cSpec.setImage(fbp.getContainerImage());
        cSpec.setCmd(fbp.getContainerCmd());
        Map<String, String> env = fbp.getContainerEnv();
        if (env == null) {
            env = new HashMap<>();
        }

        String contextPath = SessionHelper.getContextPath(environment, true);
        env.put("SHINYPROXY_PUBLIC_PATH", contextPath + "app_direct/filebrowser/");

        cSpec.setEnv(env);
        cSpec.setNetwork(fbp.getContainerNetwork());
        cSpec.setVolumes(fbp.getContainerVolumes());
        cSpec.setMemoryLimit(fbp.getContainerMemoryLimit());
        cSpec.setCpuLimit(fbp.getContainerCpuLimit());
        cSpec.setLabels(fbp.getLabels());
        Map<String, Integer> portMapping = new HashMap<>();
        if (fbp.getPort() > 0) {
            portMapping.put("default", fbp.getPort());
        } else {
            portMapping.put("default", 3838);
        }
        cSpec.setPortMapping(portMapping);

        spec.setContainerSpecs(Collections.singletonList(cSpec));
        return spec;
    }

    private boolean awaitReady(Proxy proxy) {
        if (proxy == null) return false;
        if (proxy.getStatus() == ProxyStatus.Up) return true;
        if (proxy.getStatus() == ProxyStatus.Stopping || proxy.getStatus() == ProxyStatus.Stopped) return false;

        int totalWaitMs = Integer.parseInt(environment.getProperty("proxy.container-wait-time", "20000"));
        int waitMs = Math.min(500, totalWaitMs);
        int maxTries = totalWaitMs / waitMs;
        Retrying.retry(i -> proxy.getStatus() != ProxyStatus.Starting, maxTries, waitMs);

        return (proxy.getStatus() == ProxyStatus.Up);
    }

    private String buildContainerPath(HttpServletRequest request){
        String queryString = ServletUriComponentsBuilder.fromRequest(request).replaceQueryParam("sp_hide_navbar").build().getQuery();
        queryString = (queryString == null) ? "" : "?" + queryString;
        Pattern containerPathPattern = Pattern.compile(".*?/filebrowser[/]*(.*)");
        Matcher matcher = containerPathPattern.matcher(request.getRequestURI());
        String containerPath = matcher.find() ? matcher.group(1) + queryString : queryString;
        return getContextPath() + "app_direct/filebrowser" + "/" + containerPath;
    }
}
