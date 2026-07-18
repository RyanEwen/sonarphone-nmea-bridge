import esphome.codegen as cg
import esphome.config_validation as cv
from esphome.components import sensor
from esphome.components.mipi_rgb.display import mipi_rgb as MipiRgbClass
from esphome.const import CONF_ID

# LVGL provides the display buffer we draw the waterfall into; network gives us
# the socket stack. Depend on both so codegen orders them before us.
DEPENDENCIES = ["wifi", "lvgl"]
AUTO_LOAD = ["sensor"]
CODEOWNERS = ["@RyanEwen"]

# Plain string keys / constants — robust across ESPHome versions (some of these
# aren't exported from esphome.const in every release).
CONF_HOST = "host"
CONF_PORT = "port"
CONF_FEET = "feet"
CONF_BEAM = "beam"
CONF_DEMO = "demo"
CONF_DEPTH = "depth"
CONF_TEMPERATURE = "temperature"
CONF_BATTERY = "battery"

UNIT_METER = "m"
UNIT_CELSIUS = "°C"
UNIT_VOLT = "V"
DEVICE_CLASS_DISTANCE = "distance"
DEVICE_CLASS_TEMPERATURE = "temperature"
DEVICE_CLASS_VOLTAGE = "voltage"
STATE_CLASS_MEASUREMENT = "measurement"

sp200a_ns = cg.esphome_ns.namespace("sp200a")
SP200AClient = sp200a_ns.class_("SP200AClient", cg.Component)

CONFIG_SCHEMA = cv.Schema(
    {
        cv.GenerateID(): cv.declare_id(SP200AClient),
        cv.Optional(CONF_HOST, default="192.168.1.1"): cv.string,
        cv.Optional(CONF_PORT, default=5000): cv.port,
        # feet only changes on-screen unit labelling; the T-Box is always asked
        # for metres so the renderer/sensors work in one unit internally.
        cv.Optional(CONF_FEET, default=False): cv.boolean,
        # transducer beam select (echoed at REDYFC byte 32):
        #   0x08 = 200kHz/20deg narrow, 0x02 = 80kHz/40deg wide
        cv.Optional(CONF_BEAM, default=0x08): cv.int_range(min=0, max=255),
        # demo: synthesize a waterfall with no T-Box present (bench testing)
        cv.Optional(CONF_DEMO, default=False): cv.boolean,
        # our forked mipi_rgb display: when set, LVGL is switched to DIRECT
        # rendering into the panel's two framebuffers (tear-free vsync swap,
        # no intermediate copy)
        cv.Optional("direct_display"): cv.use_id(MipiRgbClass),
        cv.Optional(CONF_DEPTH): sensor.sensor_schema(
            unit_of_measurement=UNIT_METER,
            accuracy_decimals=2,
            device_class=DEVICE_CLASS_DISTANCE,
            state_class=STATE_CLASS_MEASUREMENT,
        ),
        cv.Optional(CONF_TEMPERATURE): sensor.sensor_schema(
            unit_of_measurement=UNIT_CELSIUS,
            accuracy_decimals=0,
            device_class=DEVICE_CLASS_TEMPERATURE,
            state_class=STATE_CLASS_MEASUREMENT,
        ),
        cv.Optional(CONF_BATTERY): sensor.sensor_schema(
            unit_of_measurement=UNIT_VOLT,
            accuracy_decimals=2,
            device_class=DEVICE_CLASS_VOLTAGE,
            state_class=STATE_CLASS_MEASUREMENT,
        ),
    }
).extend(cv.COMPONENT_SCHEMA)


async def to_code(config):
    var = cg.new_Pvariable(config[CONF_ID])
    await cg.register_component(var, config)
    cg.add(var.set_host(config[CONF_HOST]))
    cg.add(var.set_port(config[CONF_PORT]))
    cg.add(var.set_feet(config[CONF_FEET]))
    cg.add(var.set_beam(config[CONF_BEAM]))
    cg.add(var.set_demo(config[CONF_DEMO]))
    if "direct_display" in config:
        disp = await cg.get_variable(config["direct_display"])
        cg.add(var.set_direct_display(disp))

    if CONF_DEPTH in config:
        cg.add(var.set_depth_sensor(await sensor.new_sensor(config[CONF_DEPTH])))
    if CONF_TEMPERATURE in config:
        cg.add(var.set_temp_sensor(await sensor.new_sensor(config[CONF_TEMPERATURE])))
    if CONF_BATTERY in config:
        cg.add(var.set_battery_sensor(await sensor.new_sensor(config[CONF_BATTERY])))
