package com.devicehive.controller;

import com.devicehive.auth.HivePrincipal;
import com.devicehive.auth.HiveRoles;
import com.devicehive.dao.DeviceCommandDAO;
import com.devicehive.json.adapters.TimestampAdapter;
import com.devicehive.json.strategies.JsonPolicyApply;
import com.devicehive.json.strategies.JsonPolicyDef.Policy;
import com.devicehive.messages.handler.RestHandlerCreator;
import com.devicehive.messages.subscriptions.*;
import com.devicehive.messages.util.Params;
import com.devicehive.model.Device;
import com.devicehive.model.DeviceCommand;
import com.devicehive.model.ErrorResponse;
import com.devicehive.model.User;
import com.devicehive.service.DeviceCommandService;
import com.devicehive.service.DeviceService;
import com.devicehive.service.UserService;
import com.devicehive.utils.RestParametersConverter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.annotation.security.RolesAllowed;
import javax.inject.Inject;
import javax.ws.rs.*;
import javax.ws.rs.container.ContainerRequestContext;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.SecurityContext;
import java.sql.Timestamp;
import java.util.List;
import java.util.UUID;

/**
 * REST controller for device commands: <i>/device/{deviceGuid}/command</i>.
 * See <a href="http://www.devicehive.com/restful#Reference/DeviceCommand">DeviceHive RESTful API: DeviceCommand</a> for details.
 */
@Path("/device/{deviceGuid}/command")
public class DeviceCommandController {

    private static final Logger logger = LoggerFactory.getLogger(DeviceCommandController.class);

    @Inject
    private DeviceCommandService commandService;

    @Inject
    private DeviceService deviceService;

    @Inject
    private UserService userService;

    @Inject
    private DeviceCommandDAO deviceCommandDAO;

    @Inject
    private DeviceCommandService deviceCommandService;

    @Inject
    private SubscriptionManager subscriptionManager;


    /**
     * Implementation of <a href="http://www.devicehive.com/restful#Reference/DeviceCommand/poll">DeviceHive RESTful API: DeviceCommand: poll</a>
     *
     * @param deviceGuid   Device unique identifier.
     * @param timestampUTC Timestamp of the last received command (UTC). If not specified, the server's timestamp is taken instead.
     * @param waitTimeout  Waiting timeout in seconds (default: 30 seconds, maximum: 60 seconds). Specify 0 to disable waiting.
     * @return Array of <a href="http://www.devicehive.com/restful#Reference/DeviceCommand">DeviceCommand</a>
     */
    @GET
    @RolesAllowed({HiveRoles.CLIENT, HiveRoles.DEVICE, HiveRoles.ADMIN})
    @JsonPolicyApply(Policy.COMMAND_TO_DEVICE)
    @Path("/poll")
    public Response poll(
            @PathParam("deviceGuid") String deviceGuid,
            @QueryParam("timestamp") String timestampUTC,
            @QueryParam("waitTimeout") String waitTimeout,
            @Context SecurityContext securityContext) {

        User user = ((HivePrincipal) securityContext.getUserPrincipal()).getUser();


        if (user == null) {
            return ResponseFactory.response(Response.Status.FORBIDDEN);
        }

        logger.debug("DeviceCommand poll requested");

        if (deviceGuid == null) {
            logger.debug("DeviceCommand poll request failed. deviceGuid is required.");
            return ResponseFactory.response(Response.Status.BAD_REQUEST);
        }

        Device device = deviceService.findByUUID(UUID.fromString(deviceGuid), user);

        if (device == null) {
            logger.debug("DeviceCommand poll request failed. No device found for device with guid = " + deviceGuid);
            return ResponseFactory.response(Response.Status.NOT_FOUND);
        }

        Timestamp timestamp = TimestampAdapter.parseTimestampQuietly(timestampUTC);
        long timeout = Params.parseWaitTimeout(waitTimeout);

        List<DeviceCommand> list = deviceCommandDAO.getNewerThan(device, timestamp);

        if (list.isEmpty()) {
            CommandSubscriptionStorage storage = subscriptionManager.getCommandSubscriptionStorage();
            String reqId = UUID.randomUUID().toString();
            RestHandlerCreator restHandlerCreator = new RestHandlerCreator(storage, device.getId(), reqId);
            CommandSubscription commandSubscription =
                new CommandSubscription(device.getId(), reqId, restHandlerCreator);
            storage.insert(commandSubscription);
            SimpleWait.waitFor(restHandlerCreator.getFutureTask(), timeout);
            storage.remove(commandSubscription);
        }
        list = deviceCommandDAO.getNewerThan(device, timestamp);

        logger.debug("DeviceCommand poll proceed successfully");

        return ResponseFactory.response(Response.Status.OK, list);
    }

    /**
     * Implementation of <a href="http://www.devicehive.com/restful#Reference/DeviceCommand/wait">DeviceHive RESTful API: DeviceCommand: wait</a>
     *
     * @param waitTimeout Waiting timeout in seconds (default: 30 seconds, maximum: 60 seconds). Specify 0 to disable waiting.
     * @return One of <a href="http://www.devicehive.com/restful#Reference/DeviceCommand">DeviceCommand</a>
     */
    @GET
    @RolesAllowed({HiveRoles.CLIENT, HiveRoles.ADMIN})
    @Path("/{commandId}/poll")
    public Response wait(
            @PathParam("deviceGuid") String deviceGuid,
            @PathParam("commandId") Long commandId,
            @QueryParam("waitTimeout") String waitTimeout,
            @Context SecurityContext securityContext) {

        logger.debug("DeviceCommand wait requested");

        User user = ((HivePrincipal) securityContext.getUserPrincipal()).getUser();

        if (deviceGuid == null || commandId == null) {
            logger.debug("DeviceCommand wait request failed. Bad request for sortOrder.");
            return ResponseFactory.response(Response.Status.BAD_REQUEST);
        }

        Device device = deviceService.findByUUID(UUID.fromString(deviceGuid), user);

        if (device == null) {
            logger.debug("DeviceCommand wait request failed. No device found with guid = " + deviceGuid);
            return ResponseFactory.response(Response.Status.NOT_FOUND);
        }
        /*    No need to check user permissions on command.
         *    We'll fail request, if this command is not sent for device user has access to.
         */

        DeviceCommand command = commandService.findById(commandId);

        if (command == null) {
            logger.debug("DeviceCommand wait request failed. No command found with id = " + commandId);
            return ResponseFactory.response(Response.Status.NOT_FOUND);
        }

        //command is not for requested device
        if (!command.getDevice().getId().equals(device.getId())) {
            logger.debug("DeviceCommand wait request failed. Command with id = " + commandId + " was not sent for " +
                    "device with guid = " + deviceGuid);
            ResponseFactory.response(Response.Status.BAD_REQUEST);
        }

        long timeout = Params.parseWaitTimeout(waitTimeout);

        if (command.getEntityVersion() == 0) {
            CommandUpdateSubscriptionStorage storage = subscriptionManager.getCommandUpdateSubscriptionStorage();
            String reqId = UUID.randomUUID().toString();
            RestHandlerCreator restHandlerCreator = new RestHandlerCreator(storage, command.getId(), reqId);
            CommandUpdateSubscription commandSubscription =
                new CommandUpdateSubscription(command.getId(), reqId, restHandlerCreator);
            storage.insert(commandSubscription);
            SimpleWait.waitFor(restHandlerCreator.getFutureTask(), timeout);
            storage.remove(commandSubscription);
        }
        command = commandService.findById(commandId);


        DeviceCommand response = command.getEntityVersion() > 0 ? command : null;

        logger.debug("DeviceCommand wait proceed successfully");

        return ResponseFactory.response(Response.Status.OK, response, Policy.COMMAND_TO_DEVICE);
    }

    /**
     * Example response:
     * <p/>
     * <code>
     * [
     * {
     * "id": 1
     * "timestamp":     "1970-01-01 00:00:00.0",
     * "userId":    1,
     * "command":   "command_name",
     * "parameters":    {/ *command parameters* /},
     * "lifetime": 10,
     * "flags":0,
     * "status":"device_status",
     * "result":{/ * result, JSON object* /}
     * },
     * {
     * "id": 2
     * "timestamp":     "1970-01-01 00:00:00.0",
     * "userId":    1,
     * "command":   "command_name",
     * "parameters":    {/ * command parameters * /},
     * "lifetime": 10,
     * "flags":0,
     * "status":"device_status",
     * "result":{/ * result, JSON object* /}
     * }
     * ]
     * </code>
     *
     * @param guid      UUID, string like "550e8400-e29b-41d4-a716-446655440000"
     * @param start     start date in format "yyyy-MM-dd'T'HH:mm:ss.SSS"
     * @param end       end date in format "yyyy-MM-dd'T'HH:mm:ss.SSS"
     * @param command   filter by command
     * @param status    filter by status
     * @param sortField either "Timestamp", "Command" or "Status"
     * @param sortOrder ASC or DESC
     * @param take      like mysql LIMIT
     * @param skip      like mysql OFFSET
     * @return list of device command with status 200, otherwise empty response with status 400
     */
    @GET
    @RolesAllowed({HiveRoles.CLIENT, HiveRoles.DEVICE, HiveRoles.ADMIN})
    public Response query(@PathParam("deviceGuid") String guid,
                          @QueryParam("start") String start,
                          @QueryParam("end") String end,
                          @QueryParam("command") String command,
                          @QueryParam("status") String status,
                          @QueryParam("sortField") String sortField,
                          @QueryParam("sortOrder") String sortOrder,
                          @QueryParam("take") Integer take,
                          @QueryParam("skip") Integer skip,
                          @Context ContainerRequestContext requestContext) {


        logger.debug("Device command query requested");

        Boolean sortOrderAsc = RestParametersConverter.isSortAsc(sortOrder);

        if (sortOrderAsc == null) {
            logger.debug("Device command query failed. Bad request for sortOrder.");
            return ResponseFactory.response(Response.Status.BAD_REQUEST, new ErrorResponse(ErrorResponse.WRONG_SORT_ORDER_PARAM_MESSAGE));
        }


        if (!"Timestamp".equals(sortField) && !"Command".equals(sortField) && !"Status".equals(sortField) && sortField != null) {
            logger.debug("Device command query failed. Bad request for sortField.");
            return ResponseFactory.response(Response.Status.BAD_REQUEST,
                    new ErrorResponse(ErrorResponse.INVALID_REQUEST_PARAMETERS_MESSAGE));
        }

        if (sortField == null) {
            sortField = "timestamp";
        }

        sortField = sortField.toLowerCase();
        Timestamp startTimestamp = null, endTimestamp = null;

        if (start != null) {
            startTimestamp = TimestampAdapter.parseTimestampQuietly(start);
            if (startTimestamp == null) {
                logger.debug("Device command query failed. Unparseable timestamp.");
                return ResponseFactory.response(Response.Status.BAD_REQUEST,
                        new ErrorResponse(ErrorResponse.INVALID_REQUEST_PARAMETERS_MESSAGE));
            }
        }

        if (end != null) {
            endTimestamp = TimestampAdapter.parseTimestampQuietly(end);
            if (endTimestamp == null) {
                logger.debug("Device command query failed. Unparseable timestamp.");
                return ResponseFactory.response(Response.Status.BAD_REQUEST,
                        new ErrorResponse(ErrorResponse.INVALID_REQUEST_PARAMETERS_MESSAGE));
            }
        }

        Device device;

        try {
            device = deviceService.getDevice(guid, (HivePrincipal) requestContext.getSecurityContext().getUserPrincipal());
        } catch (BadRequestException e) {
            return ResponseFactory
                    .response(Response.Status.BAD_REQUEST, new ErrorResponse(ErrorResponse.INVALID_REQUEST_PARAMETERS_MESSAGE));
        } catch (NotFoundException e) {
            return ResponseFactory.response(Response.Status.NOT_FOUND, new ErrorResponse(ErrorResponse.INVALID_REQUEST_PARAMETERS_MESSAGE));
        }

        List<DeviceCommand> commandList = commandService.queryDeviceCommand(device, startTimestamp, endTimestamp, command,
                status, sortField, sortOrderAsc, take, skip);

        logger.debug("Device command query request proceed successfully");

        return ResponseFactory.response(Response.Status.OK, commandList, Policy.COMMAND_TO_DEVICE);
    }

    /**
     * Response contains following output:
     * <p/>
     * <code>
     * {
     * "id":    1
     * "timestamp":     "1970-01-01 00:00:00.0"
     * "userId":    1
     * "command":   "command_name"
     * "parameters":    {/ * JSON Object * /}
     * "lifetime":  100
     * "flags":     1
     * "status":    "comand_status"
     * "result":    { / * JSON Object* /}
     * }
     * </code>
     *
     * @param guid String with Device UUID like "550e8400-e29b-41d4-a716-446655440000"
     * @param id   command id
     */
    @GET
    @RolesAllowed({HiveRoles.CLIENT, HiveRoles.DEVICE, HiveRoles.ADMIN})
    @Path("/{id}")
    @JsonPolicyApply(Policy.COMMAND_TO_DEVICE)
    public Response get(@PathParam("deviceGuid") String guid, @PathParam("id") long id, @Context ContainerRequestContext requestContext) {

        logger.debug("Device command get requested");

        Device device;
        try {
            device = deviceService.getDevice(guid, (HivePrincipal) requestContext.getSecurityContext().getUserPrincipal());
        } catch (BadRequestException e) {
            return ResponseFactory.response(Response.Status.BAD_REQUEST,
                    new ErrorResponse(ErrorResponse.INVALID_REQUEST_PARAMETERS_MESSAGE));
        } catch (NotFoundException e) {
            return ResponseFactory.response(Response.Status.NOT_FOUND, new ErrorResponse(ErrorResponse.DEVICE_NOT_FOUND_MESSAGE));
        }

        DeviceCommand result = commandService.getByGuidAndId(device.getGuid(), id);

        if (result == null) {
            logger.debug("Device command get failed. No command with id = " + id + " found for device with guid = " +
                    guid);
            return ResponseFactory.response(Response.Status.NOT_FOUND);
        }

        logger.debug("Device command get proceed successfully");

        return ResponseFactory.response(Response.Status.OK, result);
    }

    /**
     * <b>Creates new device command.</b>
     * <p/>
     * <i>Example request:</i>
     * <code>
     * {
     * "command":   "command name",
     * "parameters":    {/ * Custom Json Object * /},
     * "lifetime": 0,
     * "flags": 0
     * }
     * </code>
     * <p>
     * Where,
     * command  is Command name, required
     * parameters   Command parameters, a JSON object with an arbitrary structure. is not required
     * lifetime     Command lifetime, a number of seconds until this command expires. is not required
     * flags    Command flags, and optional value that could be supplied for device or related infrastructure. is not required\
     * </p>
     * <p>
     * <i>Example response:</i>
     * </p>
     * <code>
     * {
     * "id": 1,
     * "timestamp": "1970-01-01 00:00:00.0",
     * "userId":    1
     * }
     * </code>
     *
     * @param guid
     * @param deviceCommand
     */
    @POST
    @RolesAllowed({HiveRoles.CLIENT, HiveRoles.ADMIN})
    @Consumes(MediaType.APPLICATION_JSON)
    public Response insert(@PathParam("deviceGuid") String guid, DeviceCommand deviceCommand, @Context ContainerRequestContext requestContext) {
        String login = requestContext.getSecurityContext().getUserPrincipal().getName();
        if (login == null) {
            return ResponseFactory.response(Response.Status.FORBIDDEN);
        }

        User u = userService.findUserWithNetworksByLogin(login);

        Device device;
        try {
            device = deviceService.getDevice(guid, (HivePrincipal) requestContext.getSecurityContext().getUserPrincipal());
        } catch (BadRequestException e) {
            return ResponseFactory.response(Response.Status.BAD_REQUEST,
                    new ErrorResponse(ErrorResponse.INVALID_REQUEST_PARAMETERS_MESSAGE));
        } catch (NotFoundException e) {
            return ResponseFactory.response(Response.Status.NOT_FOUND, new ErrorResponse(ErrorResponse.DEVICE_NOT_FOUND_MESSAGE));
        }

        deviceService.submitDeviceCommand(deviceCommand, device, u, null);

        logger.debug("Device command insert proceed successfully");

        return ResponseFactory.response(Response.Status.CREATED, deviceCommand, Policy.POST_COMMAND_TO_DEVICE);
    }

    /**
     * Implementation of <a href="http://www.devicehive.com/restful#Reference/DeviceCommand/update">DeviceHive
     * RESTful API: DeviceCommand: update</a>
     * Updates an existing device command.
     *
     * @param guid      Device unique identifier.
     * @param commandId Device command identifier.
     * @param command   In the request body, supply a <a href="http://www.devicehive
     *                  .com/restful#Reference/DeviceCommand">DeviceCommand</a> resource.
     *                  All fields are not required:
     *                  flags - Command flags, and optional value that could be supplied for
     *                  device or related infrastructure.
     *                  status - Command status, as reported by device or related infrastructure.
     *                  result - Command execution result, an optional value that could be provided by device.
     * @return If successful, this method returns an empty response body.
     */
    @PUT
    @Path("/{id}")
    @RolesAllowed({HiveRoles.DEVICE, HiveRoles.ADMIN})
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@PathParam("deviceGuid") String guid, @PathParam("id") long commandId,
                           @JsonPolicyApply(Policy.REST_COMMAND_UPDATE_FROM_DEVICE) DeviceCommand command,
                           @Context ContainerRequestContext requestContext) {
        HivePrincipal principal = (HivePrincipal) requestContext.getSecurityContext().getUserPrincipal();


        logger.debug("Device command update requested");

        UUID deviceId;

        try {
            deviceId = UUID.fromString(guid);
        } catch (IllegalArgumentException e) {
            return ResponseFactory.response(Response.Status.BAD_REQUEST, "unparseable guid: " + guid);
        }

        if (principal.getUser() == null || !principal.getUser().isAdmin()) {
            if (principal.getDevice().getGuid() != deviceId) {
                return ResponseFactory.response(Response.Status.FORBIDDEN, "access is forbidden for device with  guid: "
                        + principal.getDevice().getGuid() + " to command with device " + guid);
            }
        }

        DeviceCommand commandUpdate = commandService.getByDeviceGuidAndId(deviceId, commandId);

        if (commandUpdate == null) {
            return ResponseFactory.response(Response.Status.FORBIDDEN, "no permissions for device with guid " + guid
                    + "to update command with id " + commandId);
        }

        commandUpdate.setFlags(command.getFlags());
        commandUpdate.setStatus(command.getStatus());
        commandUpdate.setResult(command.getResult());

        deviceService.submitDeviceCommandUpdate(commandUpdate, commandUpdate.getDevice());

        logger.debug("Device command update proceed successfully");

        return ResponseFactory.response(Response.Status.CREATED);
    }

}