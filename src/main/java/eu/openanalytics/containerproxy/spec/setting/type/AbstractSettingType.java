package eu.openanalytics.containerproxy.spec.setting.type;

import javax.inject.Inject;

import eu.openanalytics.containerproxy.model.runtime.RuntimeSetting;
import eu.openanalytics.containerproxy.model.spec.ProxySpec;
import eu.openanalytics.containerproxy.model.spec.RuntimeSettingSpec;
import eu.openanalytics.containerproxy.spec.ProxySpecException;
import eu.openanalytics.containerproxy.spec.setting.IRuntimeSettingType;
import eu.openanalytics.containerproxy.spec.setting.SettingSpecMapper;

/**
 * Example runtime settings:
 *
 * proxy:
 *   specs:
 *   - name: 01_hello
 *     runtime-setting-specs:
 *     - name: container.cpu
 *       enum: [2,4,8]
 *     - name: container.memory
 *       range: [2,12]
 *
 * Supported types: String, StringPattern, StringEnum, Int, IntRange, IntEnum, Float, FloatRange, FloatEnum, ...
 *
 * - If type is omitted, can be derived from config? i.e. range -> IntRange
 * - If mapping is omitted, can be derived from name? i.e. container.cpu -> containerSpec.setCpu(int)
 *
 * Custom types: name doesn't map to a spec field. Needs a custom class to resolve.
 * E.g.
 *     - name: container.class
 *       type: MyContainerClass
 * MyContainerClass offers 3 classes: low, med, hi
 * Each class translates into several settings, e.g. cpu & memory
 */
public abstract class AbstractSettingType implements IRuntimeSettingType {

	@Inject
	protected SettingSpecMapper mapper;
	
	@Override
	public void apply(RuntimeSetting setting, RuntimeSettingSpec settingSpec, ProxySpec targetSpec) throws ProxySpecException {
		Object value = getValue(setting, settingSpec);
		if (value == null) return;
		mapper.mapValue(value, settingSpec, targetSpec);
	}

	protected abstract Object getValue(RuntimeSetting setting, RuntimeSettingSpec settingSpec);
}
